package app.revanced.patches.gamehub.network

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.CONTENT_TYPE_API
import app.revanced.patches.gamehub.CONTENT_TYPE_LOG_REQUESTS
import app.revanced.patches.gamehub.EXTENSION_PREFS
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.errorhandling.errorHandlingPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.misc.settings.addSteamSetting
import app.revanced.patches.gamehub.misc.settings.settingsMenuPatch
import app.revanced.patches.gamehub.misc.token.TOKEN_PROVIDER_CLASS
import app.revanced.patches.gamehub.misc.token.tokenResolutionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val STEAM_EXTENSION = EXTENSION_PREFS

@Suppress("unused")
val apiServerSwitchPatch = bytecodePatch(
    name = "API server switch",
    description = "Allows switching between the official GameHub API and the EmuReady API server.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(sharedGamehubExtensionPatch, errorHandlingPatch, settingsMenuPatch, tokenResolutionPatch, creditsPatch)

    apply {
        addSteamSetting(CONTENT_TYPE_API, "CONTENT_TYPE_API")
        addSteamSetting(CONTENT_TYPE_LOG_REQUESTS, "CONTENT_TYPE_LOG_REQUESTS")

        // Patch both NetOkHttpInterceptor classes to add browser-like headers needed by
        // the EmuReady Cloudflare Worker endpoint.
        fun injectCompatibilityHeaders(method: MutableMethod) {
            method.apply {
                val newBuilderIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_VIRTUAL &&
                        (this as? ReferenceInstruction)?.reference?.let {
                            it is MethodReference && it.name == "newBuilder" &&
                                it.returnType == $$"Lokhttp3/Request$Builder;"
                        } == true
                }
                val builderReg = getInstruction<OneRegisterInstruction>(newBuilderIndex + 1).registerA
                addInstructions(
                    newBuilderIndex + 2,
                    $$"""
                        invoke-static {v$${builderReg}}, $${STEAM_EXTENSION}->addCompatibilityHeaders(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$${builderReg}
                        check-cast v$${builderReg}, Lokhttp3/Request$Builder;
                    """,
                )
            }
        }
        injectCompatibilityHeaders(
            firstMethod {
                definingClass == "Lcom/drake/net/interceptor/NetOkHttpInterceptor;" &&
                    implementation?.instructions?.any { instruction ->
                        (instruction as? ReferenceInstruction)?.reference?.let {
                            it is MethodReference && it.name == "newBuilder" &&
                                it.returnType == $$"Lokhttp3/Request$Builder;"
                        } == true
                    } == true
            },
        )
        injectCompatibilityHeaders(
            firstMethod {
                definingClass == "Lcom/xj/adb/wifiui/net/interceptor/NetOkHttpInterceptor;" &&
                    implementation?.instructions?.any { instruction ->
                        (instruction as? ReferenceInstruction)?.reference?.let {
                            it is MethodReference && it.name == "newBuilder" &&
                                it.returnType == $$"Lokhttp3/Request$Builder;"
                        } == true
                    } == true
            },
        )

        // Patch EggGameHttpConfig.<clinit>
        // The clinit selects a URL based on environment flags, then jumps to :goto_0 which
        // does sput-object to store the URL in field "b".  All four paths arrive at the sput
        // via "goto :goto_0", so the sput instruction IS :goto_0 (it carries the label).
        // addInstructions would insert before the sput but the label stays on the sput, so
        // every goto still jumps past our code.
        // Fix: replaceInstruction moves :goto_0 to our getEffectiveApiUrl call, then we
        // re-add move-result + sput so all paths go through the URL substitution.
        firstMethod("https://landscape-api.vgabc.com/") {
            definingClass == "Lcom/xj/common/http/EggGameHttpConfig;"
        }.apply {
            val sputIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SPUT_OBJECT &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is FieldReference &&
                            it.name == "b" &&
                            it.definingClass == "Lcom/xj/common/http/EggGameHttpConfig;"
                    } == true
            }
            val urlRegister = getInstruction<OneRegisterInstruction>(sputIndex).registerA

            replaceInstruction(
                sputIndex,
                "invoke-static {v$urlRegister}, $STEAM_EXTENSION->getEffectiveApiUrl(Ljava/lang/String;)Ljava/lang/String;",
            )
            addInstructions(
                sputIndex + 1,
                """
                    move-result-object v$urlRegister
                    sput-object v$urlRegister, Lcom/xj/common/http/EggGameHttpConfig;->b:Ljava/lang/String;
                """,
            )
        }

        // GsonConverter — inject logApiRequest at the very start of the method so it
        // captures ALL requests (not just errors).  Uses peekBody() in the extension
        // to read the response body without consuming it.
        val gsonConverterMethod = firstMethod {
            definingClass == "Lcom/xj/common/http/GsonConverter;" &&
                implementation?.instructions?.any { instr ->
                    instr.getReference<TypeReference>()?.type ==
                        "Lcom/drake/net/exception/ConvertException;"
                } == true
        }
        gsonConverterMethod.addInstructions(
            0,
            "invoke-static {p2}, $STEAM_EXTENSION->logApiRequest(Ljava/lang/Object;)V",
        )

        // GsonConverter — on the catch-all path (:goto_4) that would throw ConvertException,
        // return null instead so JSON parse failures from the alternative API return null.
        //
        // The :goto_4 block ends with `throw v0`. Immediately after it (dead code) is
        // `return-object v5` at :cond_5/:goto_5, reached from the null-body path above.
        // That return-object uses v5, which the ConvertException constructor arguments
        // within the try block set to PositiveByteConstant (const/16 v5, 0xc).
        // ART's verifier merges the types across all predecessors of return-object v5:
        // the null-body path gives Zero but the :goto_4 path (without our fix) would give
        // PositiveByteConstant, causing a VerifyError.
        //
        // Fix: replace new-instance with `const/4 v5, 0x0` (writing to the SAME register
        // as return-object v5), then remove the intervening instructions. The verifier now
        // sees v5 = Zero on both paths → valid reference return.
        //
        // IMPORTANT: use replaceInstruction (not removeInstruction + addInstructions) so the
        // :goto_4 catch-handler label stays on the const/4 instruction, not the return-object.
        gsonConverterMethod.apply {
            val newInstanceIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.NEW_INSTANCE &&
                    getReference<TypeReference>()?.type ==
                    "Lcom/drake/net/exception/ConvertException;"
            }

            val throwIndex = indexOfFirstInstructionOrThrow(newInstanceIndex) {
                opcode == Opcode.THROW
            }

            // The instruction at throwIndex+1 is `return-object v5` (dead code after throw).
            // Use the same register in our const/4 so the verifier sees v5=Zero on the
            // catch path (eliminating the PositiveByteConstant from the constructor args).
            val returnReg = getInstruction<OneRegisterInstruction>(throwIndex + 1).registerA

            // Remove from throw down to new-instance+1, leaving new-instance in place.
            for (i in throwIndex downTo newInstanceIndex + 1) {
                removeInstruction(i)
            }

            // Replace new-instance with const/4 null on returnReg, preserving :goto_4 label.
            // The now-adjacent return-object v5 acts as our null return.
            replaceInstruction(newInstanceIndex, "const/4 v$returnReg, 0x0")

            // Log full request/response details on the 4xx path (RequestParamsException).
            // At the new-instance instruction: p2 = Response, v1 = body string (or null).
            val reqParamsIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.NEW_INSTANCE &&
                    getReference<TypeReference>()?.type ==
                    "Lcom/drake/net/exception/RequestParamsException;"
            }
            addInstructions(
                reqParamsIndex,
                "invoke-static {p2, v1}, $STEAM_EXTENSION->logFailedApiRequest(Ljava/lang/Object;Ljava/lang/String;)V",
            )
        }

        // Patch wifiui HttpConfig.b(Context)
        // The method has a hardcoded const-string URL passed directly as the first argument to NetConfig.l().
        // Smali: invoke-virtual {p0, v1, p1, v0}, NetConfig;->l(String;Context;Function1;)V
        // In Instruction35c layout, registerD holds the first method argument (the URL string, v1).
        // We intercept just before the call to optionally replace the URL register.
        firstMethod("https://landscape-api.vgabc.com/") {
            definingClass == "Lcom/xj/adb/wifiui/http/HttpConfig;" && name == "b"
        }.apply {
            val netConfigCallIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "l" &&
                            it.definingClass == "Lcom/xj/adb/wifiui/net/NetConfig;"
                    } == true
            }
            // registerD is the first method argument (URL string) in invoke-virtual {instance, v1, ...}
            val urlRegister = getInstruction<Instruction35c>(netConfigCallIndex).registerD

            addInstructions(
                netConfigCallIndex,
                """
                    invoke-static {v$urlRegister}, $STEAM_EXTENSION->getEffectiveApiUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$urlRegister
                """,
            )
        }

        // Set TokenProvider.apiSwitchPatched = true so the token resolution extension
        // knows that the API switch patch is active (guards against the SharedPreferences
        // default of true when the patch isn't applied).
        firstMethod {
            definingClass == TOKEN_PROVIDER_CLASS && name == "<clinit>"
        }.apply {
            val returnVoidIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.RETURN_VOID }
            addInstructions(
                returnVoidIndex,
                """
                    const/4 v0, 0x1
                    sput-boolean v0, $TOKEN_PROVIDER_CLASS->apiSwitchPatched:Z
                """,
            )
        }

        // Hook TokenRefreshInterceptor.j() — try the external token service before falling
        // through to the official jwt/refresh/token endpoint.
        // j() has .locals 7, so v0 is safe to use before the original code sets it.
        firstMethod("jwt/refresh/token") {
            definingClass == "Lcom/xj/common/http/interceptor/TokenRefreshInterceptor;" &&
                name == "j" &&
                returnType == "Ljava/lang/String;" &&
                parameterTypes.isEmpty()
        }.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $TOKEN_PROVIDER_CLASS->refreshTokenForOfficialApi()Ljava/lang/String;
                    move-result-object v0
                    if-eqz v0, :original
                    return-object v0
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }

        // Prevent logout navigation when login is bypassed.
        // In intercept(), when j() returns null the interceptor calls TheRouter.b() to get
        // the nav service, then calls .n() on it to navigate to the login screen.
        // We skip that entire block when loginBypassed=true.
        // Target: const-class p1, ILandscapeLauncherNavService → TheRouter.b() → .n()
        // We inject before the const-class instruction.
        firstMethod {
            definingClass == "Lcom/xj/common/http/interceptor/TokenRefreshInterceptor;" &&
                name == "intercept" &&
                returnType == "Lokhttp3/Response;" &&
                parameterTypes == listOf($$"Lokhttp3/Interceptor$Chain;")
        }.apply {
            val theRouterCallIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "b" &&
                            it.definingClass == "Lcom/therouter/TheRouter;"
                    } == true
            }
            // The const-class is one instruction before TheRouter.b()
            val constClassIndex = theRouterCallIndex - 1

            // Find the next instruction after the if-eqz/invoke-interface .n() block.
            // From the smali: after .n() is :cond_5, then invoke-virtual b() (build error response).
            // We want to jump to the invoke-virtual {p0, v0} b() call that builds the
            // synthetic 401 response — which is at the instruction after the .n() call's :cond_5 label.
            val navCallIndex = indexOfFirstInstructionOrThrow(theRouterCallIndex) {
                opcode == Opcode.INVOKE_INTERFACE &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "n" &&
                            it.definingClass == "Lcom/xj/common/service/ILandscapeLauncherNavService;"
                    } == true
            }
            // The build-error-response call is after the nav .n() call + :cond_5 label.
            // From the smali: :cond_5 is the instruction right after if-eqz that skips .n(),
            // and after .n() falls through to :cond_5 too. :cond_5 is the invoke-virtual b().
            val buildErrorResponseIndex = indexOfFirstInstructionOrThrow(navCallIndex) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "b" &&
                            it.definingClass == "Lcom/xj/common/http/interceptor/TokenRefreshInterceptor;" &&
                            it.returnType == "Lokhttp3/Response;"
                    } == true
            }

            addInstructionsWithLabels(
                constClassIndex,
                """
                    sget-boolean v3, $TOKEN_PROVIDER_CLASS->loginBypassed:Z
                    if-nez v3, :skip_logout
                """,
                ExternalLabel("skip_logout", getInstruction(buildErrorResponseIndex)),
            )
        }

        addCredit("API server switch", "PlayDay" to "https://github.com/playday3008")
    }
}
