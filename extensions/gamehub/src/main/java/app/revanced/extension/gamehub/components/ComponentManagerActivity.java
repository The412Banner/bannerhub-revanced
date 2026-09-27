package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component Manager — the list of components the user injected (grouped by
 * category, with type badge / version / size / state) plus Inject and
 * Remove. Reached from the Banner Tools dialog's "Components" tile.
 *
 * <p>Inject → {@link ComponentPickerActivity} (result) →
 * {@link BhComponentInjector#start}. Download → {@link ComponentDownloadActivity}
 * (online repos; injects on its own, we re-list on resume). Remove → confirm →
 * {@link BhInjectedRegistry#remove}. Both end by bouncing {@code :pcengine}
 * so the plugin re-reads {@code sp_bh_injected_components} — the pickers in
 * PC Engine settings show the change once they are reopened.
 */
public class ComponentManagerActivity extends Activity {

    private static final String TAG = "BhComponentManager";
    private static final int REQ_PICK = 4101;

    private LinearLayout listContainer;
    private TextView countTV;

    /** Entry point for the Banner Tools tile (any Activity as the launcher). */
    public static void launch(Activity host) {
        try {
            Intent i = new Intent(host, ComponentManagerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "launch failed", t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sweep ghost rows (removed here, still persisted by the plugin) so the
        // pickers and this list agree; the plugin restarts only if something changed.
        new Thread(() -> {
            final int purged = BhInjectedRegistry.purgeStaleUnifiedRows(this);
            final int unlinked = BhInjectedRegistry.sweepDanglingComponentLinks(this);
            if (purged > 0 || unlinked > 0) runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (purged > 0) BhInjectedRegistry.reloadPcEngine(this);
                Toast.makeText(this, "Cleaned " + purged + " stale registry row(s), "
                        + unlinked + " dangling prefix link(s)", Toast.LENGTH_SHORT).show();
            });
        }, "bh-component-purge").start();
        rebuild();
    }

    private void buildUi() {
        LinearLayout root = BhComponentUi.column(this);
        root.setBackgroundColor(BhComponentUi.BG);

        LinearLayout header = BhComponentUi.row(this);
        header.setBackgroundColor(BhComponentUi.HEADER);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout titles = BhComponentUi.column(this);
        titles.addView(BhComponentUi.text(this, "Component Manager", 16f, BhComponentUi.TEXT, true));
        countTV = BhComponentUi.text(this, "", 11f, 0xFF8888AA, false);
        titles.addView(countTV);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Button download = BhComponentUi.button(this, "Download", BhComponentUi.NEUTRAL_BTN);
        download.setOnClickListener(v -> openDownloads());
        LinearLayout.LayoutParams dlp = BhComponentUi.lp(dp(100), dp(40));
        dlp.rightMargin = dp(8);
        header.addView(download, dlp);
        Button inject = BhComponentUi.button(this, "+ Inject", BhComponentUi.ACCENT);
        inject.setOnClickListener(v -> openPicker());
        header.addView(inject, BhComponentUi.lp(dp(100), dp(40)));
        root.addView(header, BhComponentUi.lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        listContainer = BhComponentUi.column(this);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private void openPicker() {
        try {
            startActivityForResult(new Intent(this, ComponentPickerActivity.class), REQ_PICK);
        } catch (Throwable t) {
            Log.w(TAG, "picker launch failed", t);
            Toast.makeText(this, "Picker unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    /** "Download components" — online repos; it injects on its own and we re-list on resume. */
    private void openDownloads() {
        try {
            startActivity(new Intent(this, ComponentDownloadActivity.class));
        } catch (Throwable t) {
            Log.w(TAG, "download screen launch failed", t);
            Toast.makeText(this, "Download screen unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        String path = data.getStringExtra(ComponentPickerActivity.EXTRA_PATH);
        if (path == null || path.isEmpty()) return;
        BhComponentInjector.start(this, new File(path), changed -> { if (changed) rebuild(); });
    }

    // ── List ──────────────────────────────────────────────────────────────

    private void rebuild() {
        listContainer.removeAllViews();
        List<BhInjectedRegistry.Entry> all = BhInjectedRegistry.list(this);
        countTV.setText(all.isEmpty()
                ? "Nothing injected yet"
                : all.size() + " injected · usr/home/components + xj_downloads/component");

        if (all.isEmpty()) {
            listContainer.addView(BhComponentUi.notice(this, "No injected components",
                    "Inject a .wcp (DXVK / VKD3D / FEX / Box64 from the Winlator community), a "
                            + ".zip GPU driver package (adrenotools), a .tzst (GameHub's format) or an "
                            + "already-extracted folder. It then appears in the matching PC Engine "
                            + "settings picker (GPU driver / DXVK / VKD3D / translator).",
                    "+ Inject", this::openPicker), BhComponentUi.cardLp(this));
            return;
        }

        // Group by category in picker order; unknown types trail.
        Map<Integer, List<BhInjectedRegistry.Entry>> groups = new LinkedHashMap<>();
        for (int t : BhComponentType.SELECTABLE) groups.put(t, new ArrayList<>());
        for (BhInjectedRegistry.Entry e : all) {
            List<BhInjectedRegistry.Entry> g = groups.get(e.type);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(e.type, g);
            }
            g.add(e);
        }
        for (Map.Entry<Integer, List<BhInjectedRegistry.Entry>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) continue;
            listContainer.addView(BhComponentUi.sectionHeader(this,
                    BhComponentType.label(g.getKey()), g.getValue().size() + ""));
            for (BhInjectedRegistry.Entry e : g.getValue()) {
                listContainer.addView(entryCard(e), BhComponentUi.cardLp(this));
            }
        }
    }

    private View entryCard(final BhInjectedRegistry.Entry e) {
        LinearLayout card = BhComponentUi.card(this);

        LinearLayout top = BhComponentUi.row(this);
        LinearLayout names = BhComponentUi.column(this);
        names.addView(BhComponentUi.text(this, e.displayName, 14f, BhComponentUi.TEXT, true));
        if (e.name != null && !e.name.equals(e.displayName)) {
            names.addView(BhComponentUi.text(this, e.name, 11f, BhComponentUi.MUTED, false));
        }
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(BhComponentUi.chip(this, BhComponentType.badge(e.type),
                BhComponentUi.SURFACE_VAR, BhComponentUi.TEXT2), BhComponentUi.chipLp(this));
        if (e.format != null && !e.format.isEmpty()) {   // source format: WCP / ZIP / TZST / FOLDER
            top.addView(BhComponentUi.chip(this, e.format.toUpperCase(java.util.Locale.ROOT),
                    BhComponentUi.SURFACE_VAR, BhComponentUi.TEXT2), BhComponentUi.chipLp(this));
        }
        boolean extracted = BhInjectedRegistry.STATE_EXTRACTED.equals(e.state);
        top.addView(BhComponentUi.chip(this, extracted ? "EXTRACTED" : "ARCHIVE",
                extracted ? 0xFF1B4D2A : 0xFF2A2A5A, extracted ? 0xFF9BE7A8 : 0xFFB8B8FF),
                BhComponentUi.lp(-2, -2));
        card.addView(top);

        StringBuilder meta = new StringBuilder();
        meta.append("v").append(e.version == null || e.version.isEmpty() ? "?" : e.version);
        meta.append("  ·  ").append(BhComponentUi.humanSize(e.fileSize));
        if (e.date > 0) meta.append("  ·  ").append(BhComponentUi.humanDate(e.date));
        TextView metaTv = BhComponentUi.text(this, meta.toString(), 11f, BhComponentUi.MUTED, false);
        metaTv.setPadding(0, dp(4), 0, 0);
        card.addView(metaTv);

        String where = extracted
                ? "usr/home/components/" + e.name + "/"
                : "xj_downloads/component/" + e.name + "/" + e.version + "/"
                  + (e.fileMd5 == null || e.fileMd5.isEmpty() ? "?" : e.fileMd5) + ".tzst";
        TextView whereTv = BhComponentUi.text(this, where, 10f, BhComponentUi.DIM, false);
        whereTv.setSingleLine(true);
        whereTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(whereTv);
        if (e.source != null && !e.source.isEmpty()) {
            TextView srcTv = BhComponentUi.text(this, "from " + e.source, 10f, BhComponentUi.DIM, false);
            srcTv.setSingleLine(true);
            srcTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(srcTv);
        }

        LinearLayout actions = BhComponentUi.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        Button remove = BhComponentUi.button(this, "Remove", BhComponentUi.RED);
        remove.setOnClickListener(v -> confirmRemove(e));
        actions.addView(remove, BhComponentUi.lp(dp(100), dp(36)));
        card.addView(actions);
        return card;
    }

    private void confirmRemove(final BhInjectedRegistry.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + e.displayName + "?")
                .setMessage("Deletes the archive and/or the extracted folder and unregisters it "
                        + "from the PC engine. Games set to use it will fall back to their default.")
                .setPositiveButton("Remove", (d, w) -> {
                    final Toast working = Toast.makeText(this, "Removing …", Toast.LENGTH_SHORT);
                    working.show();
                    new Thread(() -> {
                        final boolean ok = BhInjectedRegistry.remove(this, e.name);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (ok) BhInjectedRegistry.reloadPcEngineWithToast(this);
                            else Toast.makeText(this, "Could not update the registry", Toast.LENGTH_LONG).show();
                            rebuild();
                        });
                    }, "bh-component-remove").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return BhComponentUi.dp(this, v);
    }
}
