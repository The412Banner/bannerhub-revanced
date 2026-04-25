package com.xj.landscape.launcher.ui.gamedetail;

import android.content.Context;
import app.revanced.extension.gamehub.BhSettingsExporter;
import com.xj.common.service.bean.GameDetailEntity;
import kotlin.jvm.functions.Function1;

public final class BhFrontendExportLambda implements Function1<Object, Object> {
    public final GameDetailSettingMenu menu;
    public final GameDetailEntity game;

    public BhFrontendExportLambda(GameDetailSettingMenu menu, GameDetailEntity game) {
        this.menu = menu;
        this.game = game;
    }

    @Override
    public Object invoke(Object p1) {
        Context ctx = menu.z();
        String localId = game.getLocalGameId();
        String gameId;
        if (localId != null && !localId.isEmpty()) {
            gameId = localId;
        } else {
            gameId = game.getSteamAppId();
        }
        String gameName = game.getName();
        BhSettingsExporter.showFrontendExportDialog(ctx, gameId, gameName);
        return null;
    }
}
