package app.revanced.extension.gamehub.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Download components" — the 3.8.1 online-repo flow on v6: pick a source
 * ({@link BhComponentRepo#REPOS}) → category chips + search over its feed →
 * tap = download to {@code component_downloads/} with a cancellable
 * progress dialog → hand the file to {@link BhComponentInjector} → back to
 * the {@link ComponentManagerActivity}, which lists it on resume.
 *
 * <p>Two modes on one screen, like 3.8.1 ({@code showRepos} /
 * {@code showAssets}): back from the list returns to the sources, back from
 * the sources leaves. Feeds are cached on disk; when the fetch fails the
 * cached list opens with an "offline" status and Refresh retries.
 *
 * <p>"Quick inject" (header toggle, remembered) skips the confirm dialog
 * and registers with the feed's name / category as-is; otherwise the same
 * confirm dialog as a manual inject shows, pre-filled. A name already in
 * the registry offers Replace (remove, then inject) or cancel first.
 */
public class ComponentDownloadActivity extends Activity {

    private static final String TAG = "BhComponentDownload";
    private static final String PREFS = "bh_component_downloads";
    private static final String PREF_QUICK = "quick_inject";
    private static final String PREF_LAST_REPO = "last_repo";
    /** Cards are plain views in a ScrollView; past this the search box has to narrow it. */
    private static final int MAX_ROWS = 200;

    private static final int MODE_REPOS = 0;
    private static final int MODE_LIST = 1;

    private int mode = MODE_REPOS;
    private BhComponentRepo.Repo repo;
    private final List<BhComponentRepo.Item> items = new ArrayList<>();
    private BhComponentRepo.Loaded loaded;
    private String family;            // null = all
    private String query = "";
    private boolean quickInject;
    private int loadSeq;              // stale worker results are dropped
    private boolean loading;
    private AtomicBoolean downloadCancel;

    private TextView titleTV;
    private TextView statusTV;
    private TextView quickChip;
    private Button refreshBtn;
    private LinearLayout listHeader;
    private EditText searchEt;
    private LinearLayout chipRow;
    private ScrollView scroll;
    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        quickInject = prefs().getBoolean(PREF_QUICK, false);
        buildUi();
        showRepos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loadSeq++;
        if (downloadCancel != null) downloadCancel.set(true);
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_LIST) {
            showRepos();
            return;
        }
        super.onBackPressed();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = BhComponentUi.column(this);
        root.setBackgroundColor(BhComponentUi.BG);

        LinearLayout header = BhComponentUi.row(this);
        header.setBackgroundColor(BhComponentUi.HEADER);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout titles = BhComponentUi.column(this);
        titleTV = BhComponentUi.text(this, "Download components", 16f, BhComponentUi.TEXT, true);
        titleTV.setSingleLine(true);
        titleTV.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleTV);
        statusTV = BhComponentUi.text(this, "", 11f, 0xFF8888AA, false);
        titles.addView(statusTV);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        quickChip = BhComponentUi.chip(this, "", BhComponentUi.SURFACE_VAR, BhComponentUi.TEXT2);
        quickChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        quickChip.setClickable(true);
        quickChip.setFocusable(true);
        quickChip.setOnClickListener(v -> {
            quickInject = !quickInject;
            prefs().edit().putBoolean(PREF_QUICK, quickInject).apply();
            paintQuickChip();
            Toast.makeText(this, quickInject
                    ? "Quick inject: downloads register without the confirm dialog"
                    : "Quick inject off: the confirm dialog shows before registering",
                    Toast.LENGTH_SHORT).show();
        });
        paintQuickChip();
        header.addView(quickChip, BhComponentUi.chipLp(this));

        refreshBtn = BhComponentUi.button(this, "Refresh", BhComponentUi.NEUTRAL_BTN);
        refreshBtn.setOnClickListener(v -> { if (repo != null && !loading) openRepo(repo, true); });
        header.addView(refreshBtn, BhComponentUi.lp(dp(90), dp(40)));
        root.addView(header, BhComponentUi.lp(-1, -2));

        // Search + category chips: list mode only.
        listHeader = BhComponentUi.column(this);
        listHeader.setPadding(dp(12), dp(8), dp(12), dp(4));
        searchEt = new EditText(this);
        searchEt.setHint("Search name / version");
        searchEt.setSingleLine(true);
        searchEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        searchEt.setTextColor(BhComponentUi.TEXT);
        searchEt.setHintTextColor(BhComponentUi.MUTED);
        searchEt.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchEt.setBackground(BhComponentUi.roundBg(this, BhComponentUi.CARD, 8, BhComponentUi.OUTLINE));
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                if (q.equals(query)) return;
                query = q;
                renderList();
            }
        });
        listHeader.addView(searchEt, BhComponentUi.lp(-1, -2));
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = BhComponentUi.row(this);
        chipRow.setPadding(0, dp(8), 0, 0);
        chipScroll.addView(chipRow);
        listHeader.addView(chipScroll, BhComponentUi.lp(-1, -2));
        root.addView(listHeader, BhComponentUi.lp(-1, -2));

        scroll = new ScrollView(this);
        listContainer = BhComponentUi.column(this);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private void paintQuickChip() {
        quickChip.setText(quickInject ? "Quick inject: ON" : "Quick inject: OFF");
        quickChip.setBackground(BhComponentUi.roundBg(this,
                quickInject ? BhComponentUi.ACCENT : BhComponentUi.SURFACE_VAR, 6));
        quickChip.setTextColor(quickInject ? BhComponentUi.TEXT : BhComponentUi.TEXT2);
    }

    // ── Mode 0: sources ───────────────────────────────────────────────────

    private void showRepos() {
        mode = MODE_REPOS;
        loadSeq++;
        loading = false;
        repo = null;
        titleTV.setText("Download components");
        statusTV.setText("Select a source");
        listHeader.setVisibility(View.GONE);
        refreshBtn.setVisibility(View.GONE);
        listContainer.removeAllViews();

        String last = prefs().getString(PREF_LAST_REPO, "");
        for (final BhComponentRepo.Repo r : BhComponentRepo.REPOS) {
            LinearLayout card = clickableCard();
            LinearLayout top = BhComponentUi.row(this);
            top.addView(BhComponentUi.text(this, r.name, 14f, BhComponentUi.TEXT, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            if (r.kind == BhComponentRepo.KIND_GITHUB_RELEASES) {
                top.addView(BhComponentUi.chip(this, "GITHUB", BhComponentUi.SURFACE_VAR, BhComponentUi.TEXT2),
                        BhComponentUi.chipLp(this));
            }
            if (r.id.equals(last)) {
                top.addView(BhComponentUi.chip(this, "LAST", 0xFF2A2A5A, 0xFFB8B8FF), BhComponentUi.lp(-2, -2));
            }
            card.addView(top);
            TextView blurb = BhComponentUi.text(this, r.blurb, 11f, BhComponentUi.MUTED, false);
            blurb.setPadding(0, dp(4), 0, 0);
            card.addView(blurb);
            long cached = BhComponentRepo.cachedAt(this, r);
            card.addView(BhComponentUi.text(this,
                    cached > 0 ? "cached " + BhComponentUi.humanDate(cached) : "not fetched yet",
                    10f, BhComponentUi.DIM, false));
            card.setOnClickListener(v -> openRepo(r, false));
            listContainer.addView(card, BhComponentUi.cardLp(this));
        }

        TextView foot = BhComponentUi.text(this,
                "Wine / Proton packs are not listed — the PC engine ships its own containers. "
                        + "GPU drivers arrive as .zip, everything else as .wcp; downloads land in "
                        + BhComponentDownloader.downloadDir(this).getAbsolutePath(),
                11f, BhComponentUi.DIM, false);
        foot.setPadding(0, dp(8), 0, 0);
        listContainer.addView(foot);
        scroll.scrollTo(0, 0);
    }

    // ── Mode 1: one source's list ─────────────────────────────────────────

    private void openRepo(final BhComponentRepo.Repo r, final boolean forceNetwork) {
        mode = MODE_LIST;
        boolean sameRepo = repo == r;
        repo = r;
        family = null;
        if (!sameRepo) searchEt.setText("");     // a Refresh keeps the search, a new source starts clean
        prefs().edit().putString(PREF_LAST_REPO, r.id).apply();
        titleTV.setText(r.name);
        statusTV.setText(forceNetwork ? "Refreshing …" : "Fetching …");
        listHeader.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.VISIBLE);
        refreshBtn.setEnabled(false);
        items.clear();
        loaded = null;
        chipRow.removeAllViews();
        listContainer.removeAllViews();
        listContainer.addView(BhComponentUi.notice(this, "Fetching " + r.name + " …",
                forceNetwork ? "Ignoring the cached copy." : "Falls back to the cached copy when offline.",
                null, null), BhComponentUi.cardLp(this));

        loading = true;
        final int seq = ++loadSeq;
        new Thread(() -> {
            final BhComponentRepo.Loaded result = BhComponentRepo.load(this, r, forceNetwork);
            runOnUiThread(() -> {
                if (seq != loadSeq || isFinishing() || isDestroyed()) return;
                loading = false;
                refreshBtn.setEnabled(true);
                loaded = result;
                items.addAll(result.items);
                buildChips();
                renderList();
            });
        }, "bh-component-feed").start();
    }

    private void buildChips() {
        chipRow.removeAllViews();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String f : BhComponentRepo.FAMILIES) counts.put(f, 0);
        for (BhComponentRepo.Item it : items) {
            Integer c = counts.get(it.family);
            counts.put(it.family, c == null ? 1 : c + 1);
        }
        chipRow.addView(familyChip(null, items.size()), BhComponentUi.chipLp(this));
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() == 0) continue;
            chipRow.addView(familyChip(e.getKey(), e.getValue()), BhComponentUi.chipLp(this));
        }
    }

    private TextView familyChip(final String fam, int count) {
        boolean selected = fam == null ? family == null : fam.equals(family);
        TextView chip = BhComponentUi.chip(this, BhComponentRepo.familyLabel(fam) + " · " + count,
                selected ? BhComponentUi.ACCENT : BhComponentUi.SURFACE_VAR,
                selected ? BhComponentUi.TEXT : BhComponentUi.TEXT2);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> {
            family = fam;
            buildChips();
            renderList();
        });
        return chip;
    }

    private List<BhComponentRepo.Item> filtered() {
        List<BhComponentRepo.Item> out = new ArrayList<>();
        for (BhComponentRepo.Item it : items) {
            if (family != null && !family.equals(it.family)) continue;
            if (!query.isEmpty() && !it.searchKey().contains(query)) continue;
            out.add(it);
        }
        return out;
    }

    private void renderList() {
        if (mode != MODE_LIST) return;
        listContainer.removeAllViews();
        if (loaded == null) return;

        // Status line: source of the list + errors.
        String status;
        if (loaded.fromCache) {
            status = "offline — cached list from " + BhComponentUi.humanDate(loaded.cachedAt);
        } else if (loaded.error != null && items.isEmpty()) {
            status = "fetch failed: " + loaded.error;
        } else {
            status = items.size() + " components";
        }
        statusTV.setText(status);

        if (items.isEmpty()) {
            listContainer.addView(BhComponentUi.notice(this,
                    loaded.error != null ? "Could not load " + repo.name : "Nothing injectable here",
                    loaded.error != null
                            ? loaded.error + "\nNo cached copy to fall back to."
                            : "The feed has no DXVK / VKD3D / Box64 / FEX / GPU-driver entries.",
                    "Retry", () -> openRepo(repo, true)), BhComponentUi.cardLp(this));
            return;
        }

        List<BhComponentRepo.Item> rows = filtered();
        if (rows.isEmpty()) {
            listContainer.addView(BhComponentUi.notice(this, "No match",
                    query.isEmpty()
                            ? "No " + BhComponentRepo.familyLabel(family) + " components in this source."
                            : "Nothing matches \"" + query + "\".",
                    null, null), BhComponentUi.cardLp(this));
            return;
        }
        listContainer.addView(BhComponentUi.sectionHeader(this,
                BhComponentRepo.familyLabel(family), rows.size() + (rows.size() > MAX_ROWS ? " (first " + MAX_ROWS + ")" : "")));
        int shown = 0;
        for (BhComponentRepo.Item it : rows) {
            if (shown++ >= MAX_ROWS) break;
            listContainer.addView(itemCard(it), BhComponentUi.cardLp(this));
        }
        if (rows.size() > MAX_ROWS) {
            listContainer.addView(BhComponentUi.text(this,
                    "Showing the first " + MAX_ROWS + " of " + rows.size() + " — narrow it with the search box.",
                    11f, BhComponentUi.MUTED, false));
        }
        scroll.scrollTo(0, 0);
    }

    private View itemCard(final BhComponentRepo.Item it) {
        LinearLayout card = clickableCard();

        LinearLayout top = BhComponentUi.row(this);
        TextView name = BhComponentUi.text(this, it.name, 14f, BhComponentUi.TEXT, true);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (it.hasVerCode()) {
            top.addView(BhComponentUi.chip(this, it.verCode, 0xFF2A2A5A, 0xFFB8B8FF), BhComponentUi.chipLp(this));
        }
        top.addView(BhComponentUi.chip(this, BhComponentRepo.familyBadge(it.family),
                BhComponentUi.SURFACE_VAR, BhComponentUi.TEXT2), BhComponentUi.lp(-2, -2));
        card.addView(top);

        TextView meta = BhComponentUi.text(this, it.fileName + "  ·  " + it.repoName, 11f, BhComponentUi.MUTED, false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        card.setOnClickListener(v -> startDownload(it));
        return card;
    }

    /** A card that takes taps and gamepad focus (gold ring, the GOG screens' convention). */
    private LinearLayout clickableCard() {
        final LinearLayout card = BhComponentUi.card(this);
        final GradientDrawable bg = BhComponentUi.roundBg(this, BhComponentUi.CARD, 10, BhComponentUi.OUTLINE);
        card.setBackground(bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? BhComponentUi.CARD_HI : BhComponentUi.CARD);
            bg.setStroke(dp(f ? 2 : 1), f ? 0xFFFFC107 : BhComponentUi.OUTLINE);
        });
        return card;
    }

    // ── Download ──────────────────────────────────────────────────────────

    private void startDownload(final BhComponentRepo.Item it) {
        if (downloadCancel != null && !downloadCancel.get()) return;   // one at a time
        final File dest = new File(BhComponentDownloader.downloadDir(this), it.fileName);
        final AtomicBoolean cancel = new AtomicBoolean(false);
        downloadCancel = cancel;

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(it.name);
        pd.setMessage("Downloading " + it.fileName + " …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1000);
        pd.setCancelable(false);
        pd.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", (d, w) -> cancel.set(true));
        pd.show();

        new Thread(() -> {
            String error = null;
            try {
                // setProgress / setProgressNumberFormat post to the dialog's handler — safe off-thread.
                BhComponentDownloader.download(it.url, dest, (done, total) -> {
                    if (total > 0) pd.setProgress((int) Math.min(1000L, done * 1000L / total));
                    pd.setProgressNumberFormat(BhComponentUi.humanSize(done)
                            + (total > 0 ? " / " + BhComponentUi.humanSize(total) : ""));
                }, cancel);
            } catch (InterruptedIOException e) {
                error = "cancelled";
            } catch (Throwable t) {
                Log.w(TAG, "download failed: " + it.url, t);
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final String fError = error;
            runOnUiThread(() -> {
                try { if (pd.isShowing()) pd.dismiss(); } catch (Throwable ignored) { }
                cancel.set(true);      // slot free again
                if (isFinishing() || isDestroyed()) return;
                if ("cancelled".equals(fError)) {
                    Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show();
                } else if (fError != null) {
                    Toast.makeText(this, "Download failed: " + fError, Toast.LENGTH_LONG).show();
                } else {
                    preflightInject(it, dest);
                }
            });
        }, "bh-component-download").start();
    }

    // ── Inject ────────────────────────────────────────────────────────────

    /**
     * Registry name the feed entry will get: verName minus a trailing
     * archive extension (driver feeds put the file name in verName),
     * sanitized; the file stem when that leaves nothing.
     */
    private static String registryName(BhComponentRepo.Item it) {
        String n = BhInjectedRegistry.sanitizeName(BhComponentRepo.stripKnownExt(it.name));
        if (n.isEmpty()) n = BhInjectedRegistry.sanitizeName(BhComponentRepo.stripKnownExt(it.fileName));
        return n;
    }

    /**
     * Duplicate handling before the injector sees the file: a name we
     * injected before (or a stale folder on disk) offers Replace — remove,
     * then inject; a catalog name can only be dodged by a suffix.
     */
    private void preflightInject(final BhComponentRepo.Item it, final File file) {
        final String name = registryName(it);
        final String conflict = BhInjectedRegistry.nameConflict(this, name);
        if (conflict == null) {
            injectDownloaded(it, file, name);
            return;
        }
        boolean catalog = conflict.startsWith("A catalog component");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Already there: " + name)
                .setNegativeButton(android.R.string.cancel, (d, w) -> discard(file));
        if (catalog) {
            final String alt = name + "-inj";
            b.setMessage(conflict + ". Catalog entries cannot be replaced from here — inject it as \""
                    + alt + "\" instead?")
             .setPositiveButton("Inject as " + alt, (d, w) -> {
                 if (BhInjectedRegistry.nameConflict(this, alt) != null) {
                     // Quick inject would fail on this too; the confirm dialog lets the user rename.
                     Toast.makeText(this, "\"" + alt + "\" is taken too — rename it in the confirm dialog",
                             Toast.LENGTH_LONG).show();
                     BhComponentInjector.start(this, file, changed -> onInjected(file, changed));
                     return;
                 }
                 injectDownloaded(it, file, alt);
             });
        } else {
            b.setMessage(conflict + ". Replace it with the downloaded " + it.fileName
                    + "? The old archive / folder is removed first.")
             .setPositiveButton("Replace", (d, w) -> {
                 final Toast working = Toast.makeText(this, "Removing the old one …", Toast.LENGTH_SHORT);
                 working.show();
                 new Thread(() -> {
                     final boolean ok = BhInjectedRegistry.remove(this, name);
                     runOnUiThread(() -> {
                         if (isFinishing() || isDestroyed()) return;
                         if (!ok) {
                             Toast.makeText(this, "Could not remove the old " + name, Toast.LENGTH_LONG).show();
                             discard(file);
                             return;
                         }
                         injectDownloaded(it, file, name);
                     });
                 }, "bh-component-replace").start();
             });
        }
        b.show();
    }

    /**
     * Hands the download to the injector.
     *
     * <ul>
     *   <li>Quick inject: {@link BhComponentInjector#inspect} on a worker,
     *       then {@link BhComponentInjector#injectFile} with the feed's
     *       name / category / version pre-filled — no dialog, one progress
     *       bar, reload toast.</li>
     *   <li>Otherwise {@link BhComponentInjector#start}: the same confirm
     *       dialog as a manual inject (its own inspection suggests the name
     *       from the file stem, which equals the feed name once sanitized,
     *       and the category from the archive contents).</li>
     * </ul>
     * A registered result deletes the download and returns to the manager,
     * which re-lists on resume; a cancel / failure leaves the file in
     * {@code component_downloads/} for a manual retry via the picker.
     */
    private void injectDownloaded(final BhComponentRepo.Item it, final File file, final String name) {
        final int feedType = BhComponentRepo.typeId(it.family);
        Log.i(TAG, "inject " + file.getName() + " as " + name + " type=" + feedType + " quick=" + quickInject);
        if (!quickInject) {
            BhComponentInjector.start(this, file, changed -> onInjected(file, changed));
            return;
        }

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage("Inspecting " + file.getName() + " …");
        pd.setCancelable(false);
        pd.setMax(1000);
        pd.show();

        new Thread(() -> {
            final BhComponentInjector.Inspection insp = BhComponentInjector.inspect(file);
            if (!insp.canInject()) {
                final String why = insp.problems.isEmpty() ? "unreadable" : insp.problems.get(0);
                runOnUiThread(() -> {
                    try { if (pd.isShowing()) pd.dismiss(); } catch (Throwable ignored) { }
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Cannot inject " + file.getName() + ": " + why, Toast.LENGTH_LONG).show();
                });
                return;
            }
            // Feed data wins where it is specific; the archive's own descriptor fills the rest.
            final BhComponentInjector.ComponentSpec spec = BhComponentInjector.ComponentSpec.from(insp);
            spec.name = name;
            spec.displayName = BhComponentRepo.stripKnownExt(it.name);
            if (feedType != 0) spec.type = feedType;
            if (it.hasVerCode()) spec.version = it.verCode;
            spec.source = it.url;
            runOnUiThread(() -> pd.setMessage("Injecting " + spec.displayName + " …"));

            final String[] lastPhase = { null };
            final BhComponentInjector.Result r = BhComponentInjector.injectFile(this, file, spec,
                    (phase, done, total) -> {
                        if (phase != null && !phase.equals(lastPhase[0])) {
                            lastPhase[0] = phase;
                            runOnUiThread(() -> pd.setMessage(phase + " " + spec.displayName + " …"));
                        }
                        if (total > 0) pd.setProgress((int) Math.min(1000L, done * 1000L / total));   // posts internally
                    });
            runOnUiThread(() -> {
                try { if (pd.isShowing()) pd.dismiss(); } catch (Throwable ignored) { }
                if (isFinishing() || isDestroyed()) return;
                if (r.ok) {
                    Toast.makeText(this, "Added: " + spec.displayName, Toast.LENGTH_SHORT).show();
                    BhInjectedRegistry.reloadPcEngineWithToast(this);
                } else {
                    Toast.makeText(this, "Injection failed" + (r.error != null ? ": " + r.error : ""),
                            Toast.LENGTH_LONG).show();
                }
                onInjected(file, r.ok);
            });
        }, "bh-component-quick-inject").start();
    }

    private void onInjected(File file, boolean changed) {
        if (isFinishing() || isDestroyed()) return;
        if (!changed) return;          // cancelled confirm or failed: keep the download for a retry
        discard(file);
        setResult(RESULT_OK);
        finish();                      // the manager re-lists on resume
    }

    private static void discard(File f) {
        try { if (f != null && f.isFile()) f.delete(); } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return BhComponentUi.dp(this, v);
    }
}
