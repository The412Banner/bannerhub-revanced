package app.revanced.extension.gamehub.winemu;

import java.util.HashMap;

/**
 * Canonical component order, generated from
 * bannerhub-api/simulator/v2/getComponentList (the static catalog the
 * Worker serves verbatim, filtered by type — it does NOT sort, and the
 * picker does NOT sort client-side, so this file's array order IS the
 * online order: append-style, oldest->newest (newest at the bottom).
 * Offline we sort the synthesised list by rank(name) so it matches the
 * API exactly, including curated -async/-arm64ec interleaving.
 *
 * REGENERATE when the catalog changes (script in commit msg).
 * Unknown names -> Integer.MAX_VALUE (sink to the bottom, stable).
 */
public final class OfflineComponentOrder {
    private OfflineComponentOrder() {}

    private static volatile HashMap<String,Integer> RANK;

    /** 0-based catalog position; Integer.MAX_VALUE if absent. */
    public static int rank(String name) {
        try {
            HashMap<String,Integer> r = RANK;
            if (r == null) {
                synchronized (OfflineComponentOrder.class) {
                    r = RANK;
                    if (r == null) {
                        r = new HashMap<>(ORDER.length * 2);
                        for (int i = 0; i < ORDER.length; i++) r.put(ORDER[i], i);
                        RANK = r;
                    }
                }
            }
            Integer v = (name == null) ? null : r.get(name);
            return v == null ? Integer.MAX_VALUE : v;
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    private static final String[] ORDER = {
        "Box64-0.31-b1", "Fex-20250429", "Fex_20250507", "Fex_20241214", "Fex_20250728", "Fex_20250802",
        "Fex_20250806", "Box64-0.37-b1", "Fex_20250823", "Fex_20250910", "Box64-0.28-b1", "Box64-0.37-b2",
        "Box64-0.38", "Fex-20251025", "Fex-20251029", "Box64-0.39", "Fex-20251120", "Fex-20251128",
        "Fex-20251217", "Fex-20260103", "Box64-0.4.1-2", "Fex-20260321", "Box64-0.3.6-fix", "FEXCore-2412",
        "FEXCore-2507", "FEXCore-2508", "FEXCore-2509", "FEXCore-2601-aabdded", "FEXCore-2603", "Box64-0.4.1-fix",
        "FEXCore-2510.1", "Fex_20260428", "FEXCore-2605", "Fex_20260509", "Box64-0.4.3", "Box64-Hybrid-Bionic",
        "turnip_v24.2.0_a32", "turnip_v24.2.0_R19", "turnip_v24.2.0_R22", "turnip_v24.3.0_R7", "turnip_v24.3.0-R12", "turnip_v25.0.0_R1",
        "turnip_v24.3.0_R8", "turnip_v24.3.0_R2", "turnip_v25.0.0_R5", "turnip_v25.0.0_R6", "8Elite_800.21", "Adreno_762.10",
        "turnip_v25.0.0_R8", "turnip_v25.1.0_R1", "8Elite_800.22", "8Elite-800.26", "Adreno_805.0", "turnip_v25.1.0_R5",
        "8Elite-800.30", "turnip_v25.1.0_R6", "8Elite-800.33", "turnip_v25.2.0_R1", "turnip_v25.2.0_R3_mem", "turnip_v25.2.0_R4",
        "turnip_v25.2.0_R4_mem", "turnip_v25.2.0_R5_mem", "turnip_v25.2.0_R5", "8Elite-800.34", "turnip_v25.2.0_R6_mem", "turnip_v25.2.0_R6",
        "8Elite-800.35", "Adreno_814", "turnip_v25.2.0_R7_mem", "turnip_v25.2.0_R7", "turnip_v25.2.0_R8", "Adreno_819",
        "turnip_v25.2.0_R10", "turnip_v25.2.0_R11_mem", "turnip_v25.2.0_R11", "8Elite-800.36", "turnip_v25.2.0_R12", "turnip_v25.2.0_R12_mem",
        "8Elite-800.40", "8Elite-800.46", "turnip_v25.2.0_R13", "turnip_v25.2.0_R13_mem", "turnip_v25.3.0_R3", "turnip_v25.3.0_R3_mem",
        "turnip_v25.3.0_R4", "turnip_v25.3.0_R4_mem", "turnip_v24.3.0_R6", "turnip_v24.3.0_R5", "turnip_v25.3.0_R5", "8Elite-800.51",
        "turnip_v25.3.0_R6", "turnip_v25.3.0_R6_mem", "turnip_v25.3.0_R7", "turnip_v25.3.0_R5_one_ui7", "turnip_v25.3.0_R8", "turnip_v25.3.0_R9",
        "8eGen5-842.6", "turnip_v25.3.0_R10", "turnip_v25.3.0_R11", "8eGen5-842.8", "turnip_v26.0.0_R1", "turnip_v26.0.0_R1_mem",
        "qcom-849.0", "turnip_v26.0.0_R2", "turnip_v26.0.0_R2_mem", "qcom-842.13", "turnip_v26.0.0_R3", "turnip_v26.0.0_R3_mem",
        "turnip_v26.0.0_R4", "turnip_v26.0.0_R4_mem", "qcom-842.1", "turnip_v26.0.0_R5", "turnip_v26.0.0_R5_mem", "qcom-842.16",
        "turnip_v26.0.0_R6", "turnip_v26.0.0_R6_mem", "turnip_v26.0.0_R7", "turnip_v26.0.0_R7_mem", "qcom-842.19", "qcom-800.64",
        "Turnip_v26.1.0_R5", "qcom-837.6", "qcom-840", "qcom-863.1", "turnip_v26.0.0_R8", "turnip_v26.0.0_R8_b1",
        "turnip_v26.0.0_R8_b2", "turnip_v26.0.0_R8_b3", "turnip_v26.0.0_R8_b4", "turnip_v26.0.0_R8_b5", "turnip_v26.0.0_R8_mem", "turnip_v26.1.0_R1",
        "turnip_v26.1.0_R2", "turnip_v26.1.0_R3", "turnip_v26.1.0_R4", "turnip_v26.1.0_b1", "turnip_v26.1.0_b2", "turnip_v26.1.0_b3",
        "turnip_v26.1.0_b4", "turnip_v26.1.0_b5", "turnip_v26.1.0_b6", "turnip_v26.1.0_b7", "turnip_v26.1.0_b8", "Turnip-Deck_26.0.0",
        "Turnip-v26.1.0-R3", "Turnip-R1-26.0.0", "Turnip - Oct 22, 2025 - 26.0.0_ubwc_hint", "Turnip (Danil's Fork (tu-newat-fixes)) - Oct 22, 2025 - ade4ee9aae2", "Turnip (Mesa Main) - Oct 22, 2025 - ff51e6d", "Turnip (Mesa 26.0.0 (Patched: OneUI/UBWC)) - Oct 22, 2025 - ff51e6dc9ed",
        "Turnip (Mesa Main) - Oct 28, 2025 - 32b646c", "Turnip (Mesa 26.0.0 (Patched: OneUI)) - Oct 28, 2025 - ea24dce5e37", "Turnip (Mesa Main) - Nov 01, 2025 - 2e8b89e", "Turnip (Mesa 26.0.0 (Patched: OneUI)) - Nov 01, 2025 - 2e8b89ec60f", "Turnip-Main-8b1340a", "Turnip-Main-5ac41be",
        "Turnip-Main-3b4d2c4-A6xxFix", "Mesa Turnip Driver v26.0.0", "Turnip-Main-", "Turnip-Main-a18fbd7-VK14-A6xx-MR35894", "Turnip-PixelyIon-cf8e1b81a63", "Mesa Turnip Gen8 v7",
        "Mesa Turnip Driver v26.0.0 (Autotuner)", "Mesa Turnip driver V6", "Turnip-Gen8-Hacks-81f4eb0", "Turnip-MR38808", "Turnip-PixelyIon-tu-newat-430a593", "Turnip Normal - 26.0.0-devel",
        "Turnip OneUI - 26.0.0-devel", "Turnip a6xx - 26.0.0-devel", "Turnip-Main-01cf905-A6xxFix-SDK33", "Turnip-Main", "MTR_Turnip_v1.5_A840P", "MTR_Turnip_v1.5_A8XX",
        "MTR_Turnip_v1.8.1_A840P", "MTR_Turnip_v1.8.1_A8XX", "MTR_Turnip_v1.8.2_A840P", "MTR_Turnip_v1.8.2_A8XX", "MTR_Turnip_v1.8.3_A840P", "MTR_Turnip_v1.8.3_A840P_Test",
        "MTR_Turnip_v1.8.3_A8XX", "MTR_Turnip_v1.8.4_A840P", "MTR_Turnip_v1.8.4_A840P_Test", "MTR_Turnip_v1.8.4_A8XX", "MTR_Turnip_v1.8.5_A840P", "MTR_Turnip_v1.8.5_A8XX",
        "MTR_Turnip_v1.8.7_A840P", "MTR_Turnip_v1.8.7_A8XX_a", "MTR_Turnip_v1.8.8_A840_a", "MTR_Turnip_v1.8.8_A840", "MTR_Turnip_v1.8.8_A840P_a", "MTR_Turnip_v1.8.8_A840P",
        "MTR_Turnip_v1.8.8_A8XX_a", "MTR_Turnip_v1.8.8_A8XX", "MTR_Turnip_v1.8_A840P", "MTR_Turnip_v1.8_A840P_RC3", "MTR_Turnip_v1.8_A8XX", "MTR_Turnip_v1.8_A8XX_RC3",
        "MTR_Turnip_v1.9.0_Axxx_Smart", "MTR_Turnip_v1.9.1_Axxx_b", "MTR_Turnip_v1.9.1_Axxx_p", "MTR_Turnip_v1.9.2_Axxx_b", "MTR_Turnip_v1.9.2_Axxx_p", "MTR_Turnip_v2.0.0_Axxx_b",
        "MTR_Turnip_v2.0.0_Axxx_p", "MTR_Turnip_v3.0.0_Axxx_b", "MTR_Turnip_v3.0.0_Axxx_p", "MTR_Turnip_v3.2.0_Axxx_b", "MTR_Turnip_v3.2.0_Axxx_p", "Turnip - Dec 31, 2025",
        "A8XX Draft CI", "Mesa Turnip driver v26.0.0 - M1", "Mesa 26.0 Turnip v1", "Mesa 26.0 Turnip v2", "Mesa Turnip v26.1.0-git-hotfix-14c9ff5", "Mesa Turnip v26.1.0-git_3-9e277ed",
        "Mesa-git Turnip v26.1.0-git", "A8XX Draft v11-sdkfix", "A8XX Draft v11", "A8XX MR v12.1", "A8XX MR v12", "A8XX MR v13.1",
        "A8XX MR v13", "A8XX MR v15.1", "A8XX MR v15", "A8XX MR v16", "A8XX MR v18", "A8XX MR v19",
        "A8XX MR v20.1-full-rework", "A8XX MR v20.2-versioning", "A8XX MR v20.3", "A8XX MR v20.4", "A8XX MR v20.5", "A8XX MR v20.6-nolrz",
        "A8XX MR v20.7", "A8XX MR v20", "A8XX MR v21", "A8XX MR v22", "A8XX MR v23", "A8XX MR vdont_use",
        "A8XX MR vgralloc-exp", "Mesa Turnip Gen8 V10+MR", "Mesa Turnip Gen8 V10", "Mesa Turnip Gen8 V11", "Mesa Turnip Gen8 V12", "Mesa Turnip Gen8 V13",
        "Mesa Turnip Gen8 V14", "Mesa Turnip Gen8 V15", "Mesa Turnip Gen8 V16", "Mesa Turnip Gen8 V17", "Mesa Turnip Gen8 V18", "Mesa Turnip Gen8 V19",
        "Mesa Turnip Gen8 V20", "Turnip-Gen8-V21", "Turnip-Gen8-V22", "Turnip-Gen8-V23", "Turnip-v26.1.0-R4", "Turnip-v26.1.0-R5",
        "Turnip Gen8 V24", "Turnip Autotuner v26.0.0", "Turnip v26.1.0 R2", "Mesa Turnip Gen8 V26", "Turnip v26.0.0 Auto", "Turnip-v26.1.0",
        "Turnip_v26.1.0_fix", "Turnip-Gen8-V25", "v849", "Qualcomm 863.1", "Mesa Turnip Gen8 v9", "Turnip_v26.2.0_R1",
        "SMXZ_Turnip_Gen8_V27", "SMXZ_Turnip_Autotuner_v26.1.0", "SMXZ_Turnip_v26.2.0_R1", "SMXZ_Turnip_Gen8_V28", "SMXZ_Turnip_v26.2.0_R2", "SMXZ_Turnip_Gen8_V29",
        "SMXZ_Turnip_v26.2.0_R3", "SMXZ_Turnip_v26.2.0_R3_OneUI", "SMXZ_Turnip_Gen8_V30", "WHITE_A8xx_Turnip_v25", "WHITE_A8xx_Turnip_v25.1", "WHITE_A8xx_Turnip_v25.2",
        "WHITE_A8xx_Turnip_v25.3", "WHITE_A8xx_Turnip_v26", "Turnip_26.2.0_R3_OneUI", "Turnip_v26.1.0_R6", "Turnip_v26.2.0_R3", "turnip_v26.1.0_b10",
        "turnip_v26.1.0_b11", "turnip_v26.1.0_b12", "turnip_v26.1.0_b7-git_2", "turnip_v26.1.0_b9", "turnip_v26.2.0_b1", "VIVSI_Turnip_710-720-722_v2.5.6",
        "SMXZ_Turnip_Gen8_V31", "SMXZ_Turnip_v26.2.0_R4", "SMXZ_Turnip_v26.2.0_R4_OneUI", "MTR_WN_Turnip_v1.01_Axxx_b", "MTR_WN_Turnip_v1.01_Axxx_p", "dxvk-0.96",
        "dxvk-1.5.5", "dxvk-1.7.2", "dxvk-1.7.3", "DXVK-1.7.3-async", "DXVK-1.9.4-async", "dxvk-1.10.3",
        "dxvk-1.10.3-arm64ec-async", "dxvk-1.10.3-async", "dxvk-1.12.0-sarek", "dxvk-1.12.0-sarek-dyasync", "dxvk-2.2-4-async", "dxvk-2.3.1",
        "DXVK-2.3.1-1-gplasync", "DXVK-2.3.1-1-gplasync-arm64ec", "dxvk-2.3.1-arm64ec", "dxvk-2.3.1-arm64ec-async", "dxvk-2.3.1-async", "dxvk-2.4",
        "dxvk-2.4-async", "DXVK-2.4.1-1-gplasync", "DXVK-2.4.1-1-gplasync-arm64ec", "DXVK-2.4.1-1-gplasync-arm64ec-pre-reg", "DXVK-2.4.1-1-gplasync-pre-reg", "dxvk-2.4.1-arm64ec",
        "dxvk-2.5", "dxvk-2.5-1-async", "dxvk-2.5.3", "DXVK-2.5.3-1-gplasync", "DXVK-2.5.3-1-gplasync-arm64ec", "dxvk-2.6",
        "dxvk-2.6-arm64ec-async", "DXVK-2.6.2-1-gplasync", "DXVK-2.6.2-1-gplasync-arm64ec", "DXVK-2.6.2-gplasync-arm64ec", "dxvk-2.7.1", "DXVK-2.7.1-1-gplasync",
        "DXVK-2.7.1-1-gplasync-arm64ec", "dxvk-v1.11.0-async", "dxvk-v1.11.1-mali-fix", "dxvk-v2.4.1-async", "dxvk-v2.5.2-1-async", "dxvk-v2.6-1-async",
        "dxvk-v2.6.2-1-async", "dxvk-v2.7.1-1-async", "wined3d8.0", "vkd3d-2.12", "vkd3d-2.13", "vkd3d-proton-2.14.1",
        "vkd3d-proton-3.0a", "vkd3d-proton-3.0b", "vkd3d-proton-3.0.1", "vkd3d-proton-arm64ec-3.0.1", "A Plague_Settings", "Absolum",
        "Alice_Settings", "APlague_Settings", "AssettoCorsa", "base", "BBQ_Settings", "BLEACH",
        "Bt3_Settings", "Bt4_Settings", "Cyberpunk2077", "DARK_Settings", "DontStarve_Settings", "DyingLight_Settings",
        "EuroTruck2_Settings", "Fall_Settings", "FIFA11_Settings", "FINAL FANTASY 7_Settings", "God_Settings", "goldberg",
        "GOOD", "GrimDawnController_Settings", "Gta5_Setting", "GTA5_Setting", "gta5_settings", "gujian3",
        "Hzd_Settings", "ItTakesTwo", "Kena_Settings", "Massive_Settings", "mediafoundation_lite", "MetroExodus_Settings",
        "mod.io_Settings", "MountandBlade2_Settings", "Msmm_Settings", "NFS17", "Pal7s_Settings", "Resident Evil 3",
        "Rev_Settings", "Riders_Settings", "Rock_Settings", "sifu_Settings", "SILENT HILL F", "SKR_Settings",
        "steamagent", "SteamAgent2", "TESV_Settings", "TheHinokamiChronicles2_Settings", "TheWitcher2", "TinasWonderlands_Settings",
        "Torchlight II", "Wine", "wine_9.5", "wine_9.13", "wine_9.16", "wine_10.0",
        "wine_10.6_arm64x-2", "wine_proton_9.0_arm64x", "wine_proton_9.0_x64", "wine_proton_10.0_x64", "wine_proton10.0_arm64x-2", "WRC 9",
        "Wreckfest_Settings", "WUCHANG", "WUKONG", "aairruntime", "ACM", "amstream",
        "art2k7min", "art2kmin", "atmlib", "cjkfonts", "cnc-ddraw", "d3dcompiler_42",
        "d3dcompiler_43", "d3dcompiler_46", "d3dcompiler_47", "d3dx9", "d3dx11", "DeadSpace(2023)",
        "devenum", "dirac", "directmusic", "directplay", "directshow", "dmband",
        "dmcompos", "dmime", "dmloader", "dmscript", "dmstyle", "dmsynth",
        "dmusic", "dmusic32", "dotnet20", "dotnet20sp1", "dotnet35", "dotnet35sp1",
        "dotnet40", "dotnet45", "dotnet46", "dotnet48", "dotnet50", "dotnet452",
        "dotnet461", "dotnet462", "dotnet472", "dotnetcore3", "dotnetcoredesktop3", "dotnetcoredesktop6",
        "dotnetcoredesktop7", "dotnetcoredesktop8", "dsdmo", "dsound", "dswave", "dx8vb",
        "ffdshow", "gdiplus", "gecko", "gfw", "gmdls", "id Software",
        "ie8_kb2936068", "iertutil", "jet40", "K-Lite", "l3codecx", "lavfilters702",
        "lavfilters741", "mdac28", "mediafoundation", "mfc40", "mfc42", "mono",
        "mono-10.1.0", "mono-10.3.0", "mono-10.4.1", "msasn1", "msftedit", "msls31",
        "mspatcha", "msxml3", "msxml4", "msxml6", "oalinst", "physx",
        "powershell", "powershell_core", "qasf", "qcap", "qdvd", "qedit",
        "quartz", "quicktime72", "riched20", "sqlite3", "urlmon", "vbrun6",
        "vcredist6", "vcredist6sp6", "vcredist2005", "vcredist2008", "vcredist2010", "vcredist2012",
        "vcredist2013", "vcredist2015", "vcredist2019", "vcredist2022", "VulkanRT", "webview2",
        "win7", "winhttp", "wininet", "winXP", "WRC10", "wsh57",
        "xact", "xact_x64", "xinput", "XLiveRedist", "xna31", "xna40",
        "steam_9866232", "steam_9866233", "steam_client_0403",
    };
}
