package com.planj.phone;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap from anywhere pauses tracking, one tap resumes. */
public class PrivateTileService extends TileService {
    @Override
    public void onStartListening() {
        render();
    }

    @Override
    public void onClick() {
        PrivateMode.set(this, !PrivateMode.isOn(this));
        render();
    }

    private void render() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = PrivateMode.isOn(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("planj Private");
        tile.setSubtitle(on ? "Not recording" : "Recording");
        tile.updateTile();
    }
}
