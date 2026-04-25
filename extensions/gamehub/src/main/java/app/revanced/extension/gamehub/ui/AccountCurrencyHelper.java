package app.revanced.extension.gamehub.ui;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Replaces the hardcoded currency symbols (¥/￥) with the real currency code
 * from the Steam PICS database (e.g. "Account (USD)", "Game Price (USD)").
 *
 * <p>All public methods synchronize on {@link #LOCK} because {@link #setCurrency}
 * may be called from a background thread (Steam PICS callback) while
 * {@link #updateLabel}/{@link #updateGamePriceLabel} run on the UI thread.
 */
@SuppressLint("SetTextI18n")
public final class AccountCurrencyHelper {
    private static final Object LOCK = new Object();

    private static String sCurrency = null;
    private static final List<WeakReference<TextView>> sPendingAccountLabels = new ArrayList<>();
    private static final List<WeakReference<TextView>> sPendingGamePriceLabels = new ArrayList<>();

    /**
     * Called from SteamServiceImpl.C() for every game price conversion.
     * Captures the currency code from the PICS price data, and retroactively
     * updates any labels that were rendered before the currency was known.
     */
    public static void setCurrency(String currency) {
        if (currency == null || currency.isEmpty()) return;
        synchronized (LOCK) {
            sCurrency = currency;
            flushPending(sPendingAccountLabels, "Account", currency);
            flushPending(sPendingGamePriceLabels, "Game Price", currency);
        }
    }

    private static void flushPending(List<WeakReference<TextView>> list, String prefix, String currency) {
        Iterator<WeakReference<TextView>> it = list.iterator();
        while (it.hasNext()) {
            TextView tv = it.next().get();
            it.remove();
            if (tv != null) {
                String label = prefix + " (" + currency + ")";
                tv.post(() -> tv.setText(label));
            }
        }
    }

    /**
     * Called from display code — updates the account value title TextView
     * to show the real currency instead of hardcoded ¥. If the currency
     * isn't known yet, stores a weak reference for deferred update.
     */
    public static void updateLabel(TextView labelView) {
        if (labelView == null) return;
        synchronized (LOCK) {
            if (sCurrency != null) {
                labelView.setText("Account (" + sCurrency + ")");
            } else {
                sPendingAccountLabels.add(new WeakReference<>(labelView));
            }
        }
    }

    /**
     * Finds the game price title label (an anonymous sibling of the given container)
     * and updates it to show the real currency instead of hardcoded ￥.
     */
    public static void updateGamePriceLabel(View gamePriceContainer) {
        if (gamePriceContainer == null) return;
        if (!(gamePriceContainer.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) gamePriceContainer.getParent();
        int index = parent.indexOfChild(gamePriceContainer);
        if (index < 0 || index + 1 >= parent.getChildCount()) return;
        View next = parent.getChildAt(index + 1);
        if (!(next instanceof TextView)) return;
        TextView label = (TextView) next;
        synchronized (LOCK) {
            if (sCurrency != null) {
                label.setText("Game Price (" + sCurrency + ")");
            } else {
                sPendingGamePriceLabels.add(new WeakReference<>(label));
            }
        }
    }
}
