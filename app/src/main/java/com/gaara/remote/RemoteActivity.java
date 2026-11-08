package com.gaara.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The remote-control screen for one paired TV. */
public class RemoteActivity extends Activity implements RemoteClient.Listener {
    static final String EXTRA_FP = "fp";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Devices.Device device;
    private RemoteClient client;
    private boolean visible;
    private int retryDelay = 1000;
    private TextView name, status, volume;
    private String vendorModel = "";
    private String app = "";
    private boolean powered = true;

    private final Runnable reconnect = this::connect;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Prefs.applyTheme(this, base);
    }

    private String themeAtStart;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        device = Devices.find(this, getIntent().getStringExtra(EXTRA_FP));
        if (device == null) {
            openDevices();
            return;
        }
        setContentView(R.layout.activity_remote);
        name = findViewById(R.id.tv_name);
        status = findViewById(R.id.tv_status);
        volume = findViewById(R.id.tv_volume);
        name.setText(device.name);

        ((DpadView) findViewById(R.id.dpad)).setListener(this::key);
        bind(R.id.btn_back, KeyEvent.KEYCODE_BACK);
        bind(R.id.btn_home, KeyEvent.KEYCODE_HOME);
        bind(R.id.btn_menu, KeyEvent.KEYCODE_MENU);
        bind(R.id.btn_rewind, KeyEvent.KEYCODE_MEDIA_REWIND);
        bind(R.id.btn_play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        bind(R.id.btn_forward, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        bind(R.id.btn_vol_down, KeyEvent.KEYCODE_VOLUME_DOWN);
        bind(R.id.btn_vol_up, KeyEvent.KEYCODE_VOLUME_UP);
        bind(R.id.btn_mute, KeyEvent.KEYCODE_VOLUME_MUTE);
        bind(R.id.btn_power, KeyEvent.KEYCODE_POWER);
        findViewById(R.id.btn_devices).setOnClickListener(v -> openDevices());
        findViewById(R.id.btn_apps).setOnClickListener(v -> { LaunchSheet s = new LaunchSheet(this, device, this::openApp); s.onClose = this::buildFavourites; s.show(); });
        findViewById(R.id.btn_more).setOnClickListener(this::showMore);
        themeAtStart = Prefs.theme(this);
        Devices.setLast(this, device.fingerprint);
        setupText();
        setupTabs();
        adaptLayout();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        adaptLayout();
    }

    /**
     * Fits the remote to the screen: smaller buttons on short screens so the D-pad keeps its room,
     * a centred column of at most 460 dp on wide screens, and the D-pad beside the buttons in landscape.
     */
    private void adaptLayout() {
        android.content.res.Configuration c = getResources().getConfiguration();
        Ui.applyOrientation(this);
        int w = c.screenWidthDp, h = c.screenHeightDp;
        boolean landscape = w > h && w >= 560;
        boolean shortScreen = landscape ? h < 480 : h < 720;
        String size = Prefs.buttonSize(this);
        int btn = Prefs.SIZE_SMALL.equals(size) ? 48 : Prefs.SIZE_LARGE.equals(size) ? 72 : shortScreen ? 52 : 64;
        int gap = shortScreen ? 10 : 16;

        LinearLayout controls = findViewById(R.id.controls);
        LinearLayout buttons = findViewById(R.id.buttons);
        View dpad = findViewById(R.id.dpad);
        for (int i = 0; i < buttons.getChildCount(); i++) {
            View row = buttons.getChildAt(i);
            LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) row.getLayoutParams();
            rp.topMargin = i == 0 ? 0 : Ui.dp(this, gap);
            if (i == 2) rp.height = Ui.dp(this, btn);          // volume pill
            if (i == 3) rp.height = Ui.dp(this, btn - 8);      // keyboard
            row.setLayoutParams(rp);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) row;
            for (int k = 0; k < r.getChildCount(); k++) {
                View b = r.getChildAt(k);
                if (!(b instanceof ImageButton)) continue;
                android.view.ViewGroup.LayoutParams lp = b.getLayoutParams();
                lp.width = lp.height = Ui.dp(this, btn);
                b.setLayoutParams(lp);
            }
        }

        int widthPx = Ui.dp(this, w) - Ui.dp(this, 48); // minus the screen's side padding
        LinearLayout.LayoutParams dp = (LinearLayout.LayoutParams) dpad.getLayoutParams();
        LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) buttons.getLayoutParams();
        if (landscape) {
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
            dp.width = 0; dp.height = LinearLayout.LayoutParams.MATCH_PARENT; dp.weight = 1;
            dp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
            bp.width = Math.min(Ui.dp(this, 420), widthPx / 2);
            tabRemote.setPadding(0, 0, 0, 0);
        } else {
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setGravity(android.view.Gravity.NO_GRAVITY);
            int m = Ui.dp(this, shortScreen ? 8 : 16);
            int column = Math.min(widthPx, Ui.dp(this, 460));
            if (h >= 960) {
                // Tall screen (tablet): a square D-pad, the whole remote centred, no empty gap.
                dp.width = LinearLayout.LayoutParams.MATCH_PARENT; dp.height = column - 2 * m; dp.weight = 0;
                controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
            } else {
                dp.width = LinearLayout.LayoutParams.MATCH_PARENT; dp.height = 0; dp.weight = 1;
            }
            dp.setMargins(m, Ui.dp(this, 8), m, Ui.dp(this, gap));
            bp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            int side = Math.max(0, (widthPx - Ui.dp(this, 460)) / 2);
            tabRemote.setPadding(side, 0, side, 0);
        }
        bp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        bp.weight = 0;
        dpad.setLayoutParams(dp);
        buttons.setLayoutParams(bp);

        // Files: side by side when there is room. Lists: a readable width on big screens.
        filesTab.setWide(w >= 720 && w > h);
        int listSide = Math.max(0, (widthPx - Ui.dp(this, 760)) / 2);
        appsTab.setPadding(listSide, appsTab.getPaddingTop(), listSide, appsTab.getPaddingBottom());
        transfersTab.setPadding(listSide, transfersTab.getPaddingTop(), listSide, transfersTab.getPaddingBottom());

        View nav = findViewById(R.id.bottom_nav);
        android.view.ViewGroup.MarginLayoutParams np = (android.view.ViewGroup.MarginLayoutParams) nav.getLayoutParams();
        np.height = Ui.dp(this, shortScreen ? 56 : 64);
        int side = Math.max(0, (widthPx - Ui.dp(this, 640)) / 2);
        np.setMargins(side, Ui.dp(this, shortScreen ? 8 : 12), side, 0);
        nav.setLayoutParams(np);
    }

    // ---- Bottom tabs: Télécommande · Fichiers · Applis · Transferts ----

    private static final int TAB_REMOTE = 0, TAB_FILES = 1, TAB_APPS = 2, TAB_TRANSFERS = 3;
    private View tabRemote;
    private FilesTab filesTab;
    private AppsTab appsTab;
    private TransfersTab transfersTab;
    private final LinearLayout[] navItems = new LinearLayout[4];
    private int tab = TAB_REMOTE;

    private void setupTabs() {
        android.widget.FrameLayout container = findViewById(R.id.tab_container);
        tabRemote = findViewById(R.id.tab_remote);
        filesTab = new FilesTab(this, () -> selectTab(TAB_TRANSFERS));
        appsTab = new AppsTab(this, () -> selectTab(TAB_TRANSFERS));
        transfersTab = new TransfersTab(this);
        container.addView(filesTab);
        container.addView(appsTab);
        container.addView(transfersTab);

        LinearLayout nav = findViewById(R.id.bottom_nav);
        navItems[TAB_REMOTE] = navItem(nav, R.drawable.ic_tab_remote, "Télécommande", TAB_REMOTE);
        navItems[TAB_FILES] = navItem(nav, R.drawable.ic_folder, "Fichiers", TAB_FILES);
        navItems[TAB_APPS] = navItem(nav, R.drawable.ic_apk, "Applis", TAB_APPS);
        navItems[TAB_TRANSFERS] = navItem(nav, R.drawable.ic_transfer, "Transferts", TAB_TRANSFERS);
        selectTab(TAB_REMOTE);
    }

    private LinearLayout navItem(LinearLayout nav, int icon, String label, int index) {
        LinearLayout item = Ui.column(this);
        item.setGravity(android.view.Gravity.CENTER);
        item.setBackgroundResource(R.drawable.pill_button);
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(icon);
        item.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView t = Ui.text(this, label, 11, R.color.muted);
        t.setPadding(0, Ui.dp(this, 2), 0, 0);
        t.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        item.addView(t);
        item.setContentDescription(label);
        item.setOnClickListener(v -> selectTab(index));
        nav.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return item;
    }

    private void selectTab(int index) {
        tab = index;
        if (index != TAB_REMOTE && textOpen()) closeText();
        tabRemote.setVisibility(index == TAB_REMOTE ? View.VISIBLE : View.GONE);
        filesTab.setVisibility(index == TAB_FILES ? View.VISIBLE : View.GONE);
        appsTab.setVisibility(index == TAB_APPS ? View.VISIBLE : View.GONE);
        transfersTab.setVisibility(index == TAB_TRANSFERS ? View.VISIBLE : View.GONE);
        for (int k = 0; k < navItems.length; k++) {
            int color = getColor(k == index ? R.color.accent : R.color.muted);
            ((android.widget.ImageView) navItems[k].getChildAt(0)).setColorFilter(color);
            ((TextView) navItems[k].getChildAt(1)).setTextColor(color);
            navItems[k].setBackgroundResource(k == index ? R.drawable.pill_button : 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (device == null) return;
        if (!Prefs.theme(this).equals(themeAtStart)) { recreate(); return; }
        if (filesTab != null) filesTab.onResume();
        applyPrefs();
    }

    /** The person's choices from Réglages: rows shown, screen kept on, button size, favourites. */
    private void applyPrefs() {
        LinearLayout buttons = findViewById(R.id.buttons);
        buttons.getChildAt(1).setVisibility(Prefs.showMedia(this) ? View.VISIBLE : View.GONE);
        buttons.getChildAt(3).setVisibility(Prefs.showKeyboard(this) ? View.VISIBLE : View.GONE);
        if (Prefs.keepScreenOn(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        adaptLayout();
        buildFavourites();
        refreshStatus();
    }

    // ---- Apps on the TV: launcher sheet and the favourites row ----

    private android.widget.HorizontalScrollView favScroll;

    private void openApp(String pkg) {
        if (client == null || !client.isOpen()) {
            Toast.makeText(this, "Pas connecté à la TV", Toast.LENGTH_SHORT).show();
            return;
        }
        client.openLink(LaunchSheet.link(pkg));
    }

    private void buildFavourites() {
        LinearLayout tab = (LinearLayout) tabRemote;
        if (favScroll == null) {
            favScroll = new android.widget.HorizontalScrollView(this);
            favScroll.setHorizontalScrollBarEnabled(false);
            favScroll.addView(Ui.row(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            tab.addView(favScroll, 0, lp);
        }
        LinearLayout row = (LinearLayout) favScroll.getChildAt(0);
        row.removeAllViews();
        java.util.List<String> fav = Prefs.favourites(this);
        favScroll.setVisibility(fav.isEmpty() ? View.GONE : View.VISIBLE);
        for (String pkg : fav) {
            TextView chip = Ui.text(this, Apps.label(this, pkg), 14, R.color.text);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
            chip.setOnClickListener(v -> {
                if (Prefs.haptics(this)) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                openApp(pkg);
            });
            chip.setOnLongClickListener(v -> {
                Prefs.toggleFavourite(this, pkg);
                Toast.makeText(this, Apps.label(this, pkg) + " retiré des favoris", Toast.LENGTH_SHORT).show();
                buildFavourites();
                return true;
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cl.rightMargin = Ui.dp(this, 8);
            row.addView(chip, cl);
        }
    }

    // ---- More: sleep timer, settings ----

    private void showMore(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        int left = SleepTimer.minutesLeft(this, device.fingerprint);
        m.getMenu().add(0, 1, 0, left > 0 ? "Minuterie de veille (" + left + " min)" : "Minuterie de veille…");
        m.getMenu().add(0, 2, 1, "Réglages");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showTimer();
            else startActivity(new android.content.Intent(this, SettingsActivity.class));
            return true;
        });
        m.show();
    }

    private static final int[] TIMER_MINUTES = {15, 30, 45, 60, 90, 120};

    private void showTimer() {
        int left = SleepTimer.minutesLeft(this, device.fingerprint);
        java.util.List<String> items = new java.util.ArrayList<>();
        for (int t : TIMER_MINUTES) items.add(t < 60 ? t + " minutes" : (t / 60) + " h" + (t % 60 == 0 ? "" : " " + (t % 60)));
        if (left > 0) items.add("Arrêter la minuterie");
        new android.app.AlertDialog.Builder(this)
                .setTitle(left > 0 ? "Veille dans " + left + " min" : "Mettre la TV en veille dans…")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which >= TIMER_MINUTES.length) {
                        SleepTimer.cancel(this);
                        Toast.makeText(this, "Minuterie arrêtée", Toast.LENGTH_SHORT).show();
                    } else {
                        SleepTimer.set(this, device, TIMER_MINUTES[which]);
                        Toast.makeText(this, device.name + " se mettra en veille dans " + items.get(which), Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---- Writing on the TV: keyboard + dictation ----

    private static final int REQ_MIC = 1;
    private View controls, textPanel;
    private EditText input;
    private TextView textHint;
    private ImageButton micButton;
    private Dictation dictation;
    private boolean fromTv;           // text set from the TV: don't echo it back
    private String beforeDictation = "";
    private boolean dictateWhenGranted;

    private String lastSent = "";     // what the TV field holds, as far as we know
    private boolean dictating;        // dictation shows provisional words: send only the final text

    /**
     * Sends only the difference since last time, like a real keyboard: the TV appends
     * what it receives, so removed letters become Delete presses and new ones are inserted.
     */
    private final Runnable pushText = () -> {
        if (client == null || !client.isOpen()) return;
        String now = input.getText().toString();
        int p = 0, max = Math.min(now.length(), lastSent.length());
        while (p < max && now.charAt(p) == lastSent.charAt(p)) p++;
        for (int i = lastSent.length() - p; i > 0; i--) client.key(KeyEvent.KEYCODE_DEL);
        String added = now.substring(p);
        if (!added.isEmpty()) client.insertText(added);
        lastSent = now;
    };

    private void setupText() {
        controls = findViewById(R.id.controls);
        textPanel = findViewById(R.id.text_panel);
        input = findViewById(R.id.text_input);
        textHint = findViewById(R.id.text_hint);
        micButton = findViewById(R.id.btn_mic);

        findViewById(R.id.btn_keyboard).setOnClickListener(v -> openText(false));
        voiceButton = findViewById(R.id.btn_voice);
        voiceButton.setOnClickListener(v -> toggleVoice());
        findViewById(R.id.btn_text_close).setOnClickListener(v -> closeText());
        micButton.setOnClickListener(v -> toggleDictation());
        findViewById(R.id.btn_send).setOnClickListener(v -> submit());
        input.setOnEditorActionListener((v, actionId, e) -> {
            submit();
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (fromTv || dictating) return;
                // Short delay so fast typing sends one update, not one per letter.
                handler.removeCallbacks(pushText);
                handler.postDelayed(pushText, 150);
            }
        });

        dictation = new Dictation(this, new Dictation.Listener() {
            @Override
            public void onListening() {
                micButton.setBackgroundResource(R.drawable.mic_active);
                textHint.setText(R.string.listening);
            }

            @Override
            public void onPartial(String text) {
                showDictated(text);
            }

            @Override
            public void onResult(String text) {
                showDictated(text);
            }

            @Override
            public void onDone(String error) {
                // Now that the sentence is final, send it to the TV in one go.
                dictating = false;
                handler.removeCallbacks(pushText);
                pushText.run();
                micButton.setBackgroundResource(R.drawable.round_button);
                textHint.setText(error != null ? error : getString(R.string.text_hint));
            }
        });
    }

    private boolean textOpen() {
        return textPanel.getVisibility() == View.VISIBLE;
    }

    private void openText(boolean dictate) {
        if (!textOpen()) {
            controls.setVisibility(View.GONE);
            textPanel.setVisibility(View.VISIBLE);
        }
        input.requestFocus();
        input.setSelection(input.length());
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (dictate) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            startDictation();
        } else {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeText() {
        dictation.stop();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        textPanel.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
    }

    /** "Send": the TV runs the search / validates the field, like Enter on a keyboard. */
    private void submit() {
        handler.removeCallbacks(pushText);
        pushText.run();
        handler.postDelayed(() -> key(KeyEvent.KEYCODE_ENTER), 150);
        setFromTv("");
        closeText();
    }

    private void setFromTv(String value) {
        fromTv = true;
        input.setText(value);
        input.setSelection(input.length());
        lastSent = value;
        fromTv = false;
    }

    private void toggleDictation() {
        if (dictation.isActive()) dictation.stop();
        else startDictation();
    }

    private void startDictation() {
        if (!Dictation.available(this)) {
            textHint.setText("Aucune reconnaissance vocale sur ce téléphone.");
            return;
        }
        if (!micAllowed()) {
            dictateWhenGranted = true;
            return;
        }
        beforeDictation = input.getText().toString().trim();
        // Flush any typing first, then hold sending until the sentence is final.
        handler.removeCallbacks(pushText);
        pushText.run();
        dictating = true;
        dictation.start();
    }

    /** Dictated words go after what was already written, and on to the TV. */
    private void showDictated(String spoken) {
        String text = beforeDictation.isEmpty() ? spoken : beforeDictation + " " + spoken;
        input.setText(text);
        input.setSelection(input.length());
    }

    /** True when the mic may be used; otherwise asks Android once and returns false. */
    private boolean micAllowed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && dictateWhenGranted) startDictation();
        if (ok && voiceWhenGranted) toggleVoice();
        if (!ok) {
            String m = "Sans le micro, la voix ne peut pas marcher. Tu peux l'autoriser dans les paramètres de l'appli.";
            if (textOpen()) textHint.setText(m);
            else Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        }
        dictateWhenGranted = false;
        voiceWhenGranted = false;
    }

    private int openedField;          // the TV field the panel was opened for, so closing it sticks

    @Override
    public void onTextField(String value, int key) {
        if (value == null) { // no field focused any more: the next field may open the panel again
            openedField = 0;
            return;
        }
        // Like the Google TV app: a field opens on the TV, the phone is ready to write in it.
        if (textOpen()) {
            if (key != openedField) setFromTv(value);
        } else if (key != openedField && Prefs.autoKeyboard(this) && tab == TAB_REMOTE) {
            setFromTv(value);
            openText(false);
        }
        openedField = key;
    }

    // ---- Talking to the TV (its voice assistant), like the mic button of a remote ----

    private ImageButton voiceButton;
    private final VoiceCapture capture = new VoiceCapture();
    private boolean waitingVoice, voiceWhenGranted;

    private final Runnable voiceTimeout = () -> {
        if (!waitingVoice) return;
        waitingVoice = false;
        voiceIdle();
        Toast.makeText(this, "La TV n'a pas ouvert son assistant vocal.", Toast.LENGTH_SHORT).show();
    };

    private void toggleVoice() {
        if (capture.isRunning()) {
            capture.stop();
            return;
        }
        if (waitingVoice) return;
        if (client == null || !client.isOpen()) {
            Toast.makeText(this, "Pas connecté à la TV", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!client.voiceSupported()) {
            Toast.makeText(this, "Cette TV n'accepte pas la voix par le téléphone.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!micAllowed()) {
            voiceWhenGranted = true;
            return;
        }
        voiceButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        voiceButton.setBackgroundResource(R.drawable.mic_active);
        setStatus(getString(R.string.voice_listening), R.color.danger);
        waitingVoice = true;
        client.voiceStart();
        handler.postDelayed(voiceTimeout, 3000);
    }

    @Override
    public void onVoiceBegin(int session) {
        if (!waitingVoice) return;
        waitingVoice = false;
        handler.removeCallbacks(voiceTimeout);
        RemoteClient c = client;
        capture.start(new VoiceCapture.Sink() {
            @Override
            public void audio(byte[] pcm, int length) {
                if (c.isOpen()) c.voiceAudio(session, pcm, length);
            }

            @Override
            public void done(String reason) {
                if (c.isOpen()) c.voiceEnd(session);
                voiceIdle();
                if (reason != null) Toast.makeText(RemoteActivity.this, reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void voiceIdle() {
        voiceButton.setBackgroundResource(R.drawable.round_button);
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dictation != null) dictation.release();
    }

    private void bind(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> {
            if (Prefs.haptics(this)) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            key(keyCode);
        });
    }

    private void key(int keyCode) {
        if (client == null || !client.isOpen()) {
            Toast.makeText(this, "Pas connecté à la TV", Toast.LENGTH_SHORT).show();
            return;
        }
        client.key(keyCode);
    }

    private void openDevices() {
        startActivity(new Intent(this, DevicesActivity.class).putExtra(DevicesActivity.EXTRA_NO_AUTO, true));
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (device != null) connect();
        // File sharing with the app's other copies (TV, PC): only while the app is open on the phone.
        com.gaara.remote.share.ShareHub.get(this).start();
        if (transfersTab != null) transfersTab.attach();
        if (filesTab != null) filesTab.attach();
        if (appsTab != null) appsTab.attach();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        if (transfersTab != null) transfersTab.detach();
        if (filesTab != null) filesTab.detach();
        if (appsTab != null) appsTab.detach();
        com.gaara.remote.share.ShareHub.get(this).stop();
        if (dictation != null) dictation.stop();
        capture.stop();
        waitingVoice = false;
        handler.removeCallbacks(voiceTimeout);
        handler.removeCallbacks(reconnect);
        if (client != null) client.close();
        client = null;
    }

    private void connect() {
        if (!visible) return;
        if (client != null) client.close();
        setStatus("Connexion…", R.color.warn);
        try {
            client = new RemoteClient(Identity.get(this), device.host, device.fingerprint, this);
            client.connect();
        } catch (Exception e) {
            onDisconnected(e.getMessage());
        }
    }

    // ---- RemoteClient.Listener ----

    @Override
    public void onConnected(String vendor, String model) {
        retryDelay = 1000;
        vendorModel = (vendor + " " + model).trim();
        refreshStatus();
    }

    @Override
    public void onPower(boolean on) {
        powered = on;
        refreshStatus();
    }

    @Override
    public void onApp(String packageName) {
        app = Apps.label(this, packageName);
        refreshStatus();
    }

    @Override
    public void onVolume(int level, int max, boolean muted) {
        // Some boxes (e.g. a SHIELD on HDMI-CEC) leave the volume to the TV and report max 0.
        if (max <= 0) volume.setText("");
        else volume.setText(muted ? "Muet" : level + " / " + max);
    }

    @Override
    public void onDisconnected(String reason) {
        client = null;
        if (!visible) return;
        setStatus("Déconnecté — " + (reason == null ? "" : reason), R.color.danger);
        if (reason != null && reason.contains("appairage")) return; // needs the person, not a retry
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 15000);
    }

    private void refreshStatus() {
        if (!powered) {
            setStatus("● En veille", R.color.muted);
        } else if (!app.isEmpty()) {
            setStatus("● " + app, R.color.ok);
        } else {
            setStatus("● Connecté" + (vendorModel.isEmpty() ? "" : " — " + vendorModel), R.color.ok);
        }
    }

    private void setStatus(String text, int color) {
        int left = device == null ? 0 : SleepTimer.minutesLeft(this, device.fingerprint);
        if (left > 0) text += "  ·  veille dans " + left + " min";
        status.setText(text);
        status.setTextColor(getColor(color));
    }

    // The phone's own volume buttons drive the TV while this screen is open.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && Prefs.volumeKeys(this)) {
            if (client != null && client.isOpen()) client.key(keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && Prefs.volumeKeys(this)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Back first leaves the text panel; then goes to the TV list (to pick another box).
        // The TV's own Back is the on-screen button.
        if (tab == TAB_FILES && filesTab.back()) return;
        if (tab == TAB_APPS && appsTab.back()) return;
        if (tab != TAB_REMOTE) selectTab(TAB_REMOTE);
        else if (textOpen()) closeText();
        else openDevices();
    }
}
