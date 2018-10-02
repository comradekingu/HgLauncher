package mono.hg;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import eu.davidea.flexibleadapter.FlexibleAdapter;
import mono.hg.adapters.AppAdapter;
import mono.hg.helpers.LauncherIconHelper;
import mono.hg.helpers.PreferenceHelper;
import mono.hg.helpers.RecyclerClick;
import mono.hg.models.AppDetail;
import mono.hg.models.PinnedAppDetail;
import mono.hg.receivers.PackageChangesReceiver;
import mono.hg.utils.ActivityServiceUtils;
import mono.hg.utils.AppUtils;
import mono.hg.utils.Utils;
import mono.hg.utils.ViewUtils;
import mono.hg.views.IndeterminateMaterialProgressBar;
import mono.hg.views.TogglingLinearLayoutManager;
import mono.hg.wrappers.OnTouchListener;
import mono.hg.wrappers.SimpleScrollUpListener;

public class MainActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /*
     * Should the favourites panel listen for scroll?
     */
    private boolean shouldShowFavourites = true;

    /*
     * Count of currently installed apps.
     * TODO: Better manage this.
     */
    private int current_app_count;

    /*
     * Animation duration; fetched from system's duration.
     */
    private int animateDuration;

    /*
     * List containing installed apps.
     */
    private ArrayList<AppDetail> appsList = new ArrayList<>();

    /*
     * Adapter for installed apps.
     */
    private AppAdapter appsAdapter = new AppAdapter(appsList);

    /*
     * List containing pinned apps.
     */
    private ArrayList<PinnedAppDetail> pinnedAppList = new ArrayList<>();

    /*
     * String containing pinned apps. Delimited by ';'.
     */
    private String pinnedAppString;

    /*
     * Adapter for pinned apps.
     */
    private FlexibleAdapter<PinnedAppDetail> pinnedAppsAdapter = new FlexibleAdapter<>(
            pinnedAppList);

    /*
     * List of excluded apps. These will not be shown in the app list.
     */
    private HashSet<String> excludedAppsList = new HashSet<>();

    /*
     * Package manager; casted through getPackageManager().
     */
    private PackageManager manager;

    /*
     * RecyclerView for app list.
     */
    private RecyclerView appsRecyclerView;

    /*
     * LinearLayoutManager used in appsRecyclerView.
     */
    private TogglingLinearLayoutManager appsLayoutManager;

    /*
     * RecyclerView for pinned apps; shown in favourites panel.
     */
    private RecyclerView pinnedAppsRecyclerView;

    /*
     * Parent layout containing search bar.
     */
    private FrameLayout searchContainer;

    /*
     * Parent layout of pinned apps' RecyclerView.
     */
    private FrameLayout pinnedAppsContainer;

    /*
     * Parent layout for installed app list.
     */
    private RelativeLayout appListContainer;

    /*
     * The search bar. Contained in searchContainer.
     */
    private EditText searchBar;

    /*
     * Sliding up panel. Shows the app list when pulled down and
     * a parent to the other containers.
     */
    private SlidingUpPanelLayout slidingHome;

    /*
     * CoordinatorLayout hosting the search snackbar.
     */
    private View snackHolder;

    /*
     * A view used to intercept gestures and taps in the desktop.
     */
    private View touchReceiver;

    /*
     * Progress bar shown when populating app list.
     */
    private IndeterminateMaterialProgressBar loadProgress;

    /*
     * SharedPreferences method, used to add/remove and get preferences.
     */
    private SharedPreferences prefs;
    private SharedPreferences.Editor editPrefs;

    /*
     * Menu shown when long-pressing apps.
     */
    private PopupMenu appMenu;

    /*
     * The receiver handling package installation/uninstallation.
     */
    private PackageChangesReceiver packageReceiver = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load preferences before setting layout to allow for quick theme change.
        loadPref(true);

        setContentView(R.layout.activity_main);

        manager = getPackageManager();

        appsLayoutManager = new TogglingLinearLayoutManager(this,
                LinearLayoutManager.VERTICAL, false);

        appsLayoutManager.setStackFromEnd(true);

        final LinearLayoutManager pinnedAppsManager = new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false);

        appListContainer = findViewById(R.id.app_list_container);
        searchContainer = findViewById(R.id.search_container);
        pinnedAppsContainer = findViewById(R.id.pinned_apps_container);
        searchBar = findViewById(R.id.search);
        slidingHome = findViewById(R.id.slide_home);
        touchReceiver = findViewById(R.id.touch_receiver);
        snackHolder = findViewById(R.id.snack_holder);
        appsRecyclerView = findViewById(R.id.apps_list);
        pinnedAppsRecyclerView = findViewById(R.id.pinned_apps_list);
        loadProgress = findViewById(R.id.load_progress);

        animateDuration = getResources().getInteger(android.R.integer.config_shortAnimTime);

        slidingHome.disallowHiding(true);

        // Let the launcher handle state of the panel.
        slidingHome.alwaysResetState(true);

        appsRecyclerView.setDrawingCacheEnabled(true);
        appsRecyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_LOW);
        appsRecyclerView.setHasFixedSize(true);

        appsRecyclerView.setAdapter(appsAdapter);
        appsRecyclerView.setLayoutManager(appsLayoutManager);
        appsRecyclerView.setItemAnimator(new DefaultItemAnimator());

        pinnedAppsRecyclerView.setAdapter(pinnedAppsAdapter);
        pinnedAppsRecyclerView.setLayoutManager(pinnedAppsManager);
        pinnedAppsRecyclerView.setItemAnimator(null);

        // Restore search bar visibility when panel is pulled down.
        if (savedInstanceState != null && ViewUtils.isPanelVisible(slidingHome)) {
            searchContainer.setVisibility(savedInstanceState.getInt("searchVisibility"));
        }

        // Get icons from icon pack.
        if (!"default".equals(PreferenceHelper.getIconPackName())) {
            if (AppUtils.isAppInstalled(manager, PreferenceHelper.getIconPackName())) {
                new LauncherIconHelper().loadIconPack(manager);
            } else {
                // We can't find the icon pack, so revert back to the default pack.
                editPrefs.putString("icon_pack", "default").apply();
            }
        }

        // Start loading apps and initialising click listeners.
        new getAppTask(this).execute();
        addSearchBarListener();
        addGestureListener();
        addListListeners();
        addPanelListener();

        registerForContextMenu(touchReceiver);

        registerPackageReceiver();

        // Get pinned apps.
        pinnedAppString = prefs.getString("pinned_apps_list", "");

        if (!pinnedAppString.isEmpty()) {
            for (String pinnedApp : Arrays.asList(pinnedAppString.split(";"))) {
                AppUtils.pinApp(manager, pinnedApp, pinnedAppsAdapter, pinnedAppList);
            }
        }

        applyPrefToViews();

        // Save our current app count.
        //TODO: There are better ways to accomplish this.
        current_app_count = appsList.size() - 1;
    }

    @Override public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override public boolean onContextItemSelected(MenuItem item) {
        return onOptionsItemSelected(item);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_force_refresh:
                reload();
                return true;
            case R.id.update_wallpaper:
                intent = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(Intent.createChooser(intent, getString(R.string.action_wallpaper)));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        switch (key) {
            default:
                // No-op.
                break;
            case "app_theme":
            case "shade_view_switch":
            case "comfy_padding":
            case "dummy_restore":
            case "favourites_panel_switch":
            case "icon_hide_switch":
            case "list_order":
                reload();
                break;
            case "adaptive_shade_switch":
            case "icon_pack":
                LauncherIconHelper.clearDrawableCache();
                reload();
                break;
            case "removedApp":
                editPrefs.putBoolean("removedApp", false).apply();
                editPrefs.remove("removed_app").apply();
                doThis("hide_panel");
                // FIXME: Stop using recreate here; it's bad for the UX.
                reload();
                break;
            case "addApp":
                editPrefs.putBoolean("addApp", false).apply();
                editPrefs.remove("added_app").apply();
                doThis("hide_panel");
                // FIXME: Recreate after receiving installation to handle frozen app list.
                reload();
                break;
        }
    }

    @Override public void onBackPressed() {
        // Hides the panel if back is pressed.
        doThis("hide_panel");
    }

    @Override public void onPause() {
        super.onPause();

        // Check if we need to dismiss the panel.
        if (PreferenceHelper.shouldDismissOnLeave()) {
            doThis("hide_panel");
        }

        // You shouldn't be visible.
        if (appMenu != null) {
            appMenu.dismiss();
        }
    }

    @Override public void onResume() {
        super.onResume();
        loadPref(false);

        // Reset the app list filter.
        appsAdapter.resetFilter();
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed()) {
            // Get selected text for cut and copy.
            int start = searchBar.getSelectionStart();
            int end = searchBar.getSelectionEnd();
            final String text = searchBar.getText().toString().substring(start, end);

            switch (keyCode) {
                case KeyEvent.KEYCODE_A:
                    searchBar.selectAll();
                    return true;
                case KeyEvent.KEYCODE_X:
                    searchBar.setText(searchBar.getText().toString().replace(text, ""));
                    return true;
                case KeyEvent.KEYCODE_C:
                    ActivityServiceUtils.copyToClipboard(this, text);
                    return true;
                case KeyEvent.KEYCODE_V:
                    searchBar.setText(
                            searchBar.getText().replace(Math.min(start, end), Math.max(start, end),
                                    ActivityServiceUtils.pasteFromClipboard(this), 0,
                                    ActivityServiceUtils.pasteFromClipboard(this).length()));
                    return true;
                default:
                    return super.onKeyUp(keyCode, event);
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_ESCAPE:
                    doThis("hide_panel");
                    return true;
                case KeyEvent.KEYCODE_SPACE:
                    if (!searchBar.hasFocus()) {
                        doThis("show_panel");
                    }
                    return true;
                default:
                    return super.onKeyUp(keyCode, event);
            }
        }
    }

    @Override public void onSaveInstanceState(Bundle savedInstanceState) {
        // Save search bar visibility state.
        if (ViewUtils.isPanelVisible(slidingHome)) {
            savedInstanceState.putInt("searchVisibility", View.VISIBLE);
        } else {
            savedInstanceState.putInt("searchVisibility", View.INVISIBLE);
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    private void reload() {
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
            // FIXME: Don't ignore this please.
        }
        recreate();
    }

    private void loadApps() {
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> availableActivities = manager.queryIntentActivities(i, 0);

        if (PreferenceHelper.isListInverted()) {
            Collections.sort(availableActivities, Collections
                    .reverseOrder(new ResolveInfo.DisplayNameComparator(manager)));
        } else {
            Collections.sort(availableActivities, new ResolveInfo.DisplayNameComparator(manager));
        }

        // Fetch and add every app into our list, but ignore those that are in the exclusion list.
        for (ResolveInfo ri : availableActivities) {
            String packageName = ri.activityInfo.packageName;
            if (!excludedAppsList.contains(packageName) && !packageName.equals(getPackageName())) {
                String appName = ri.loadLabel(manager).toString();
                Drawable icon = null;
                Drawable getIcon = null;
                // Only show icons if user chooses so.
                if (!PreferenceHelper.shouldHideIcon()) {
                    if (!PreferenceHelper.getIconPackName().equals("default")) {
                        getIcon = new LauncherIconHelper().getIconDrawable(manager, packageName);
                    }
                    if (getIcon == null) {
                        icon = ri.activityInfo.loadIcon(manager);
                        if (PreferenceHelper.appTheme().equals("light")
                                && PreferenceHelper.shadeAdaptiveIcon()
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                && icon instanceof AdaptiveIconDrawable) {
                            icon = LauncherIconHelper.drawAdaptiveShadow(icon);
                        }
                    } else {
                        icon = getIcon;
                    }
                }
                AppDetail app = new AppDetail(icon, appName, packageName, false);
                appsList.add(app);
            }
        }
    }

    // A method to launch an app based on package name.
    private void launchApp(String packageName) {
        Intent intent = manager.getLaunchIntentForPackage(packageName);
        // Attempt to catch exceptions instead of crash landing directly to the floor.
        try {
            Utils.requireNonNull(intent).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

            // Override app launch animation when needed.
            switch (PreferenceHelper.getLaunchAnim()) {
                case "pull_up":
                    overridePendingTransition(R.anim.pull_up, 0);
                    break;
                case "slide_in":
                    overridePendingTransition(R.anim.slide_in, 0);
                    break;
                default:
                case "default":
                    // Don't override when we have the default value.
                    break;
            }
        } catch (ActivityNotFoundException | NullPointerException e) {
            Toast.makeText(MainActivity.this, R.string.err_activity_null, Toast.LENGTH_LONG).show();
            Utils.sendLog(3, "Cannot start " + packageName + "; missing package?");
        }
    }

    private void doThis(String action) {
        switch (action) {
            default:
                // Don't do anything.
                break;
            case "show_panel":
                if (!ViewUtils.isPanelVisible(slidingHome)) {
                    slidingHome.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED,
                            ActivityServiceUtils.isPowerSaving(this));
                }
                break;
            case "hide_panel":
                if (ViewUtils.isPanelVisible(slidingHome)) {
                    slidingHome.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED,
                            ActivityServiceUtils.isPowerSaving(this));
                }
                break;
            case "show_favourites":
                pinnedAppsContainer.animate()
                                   .translationY(0f)
                                   .setInterpolator(new FastOutSlowInInterpolator())
                                   .setDuration(animateDuration)
                                   .setListener(new AnimatorListenerAdapter() {
                                       @Override
                                       public void onAnimationStart(Animator animator) {
                                           super.onAnimationStart(animator);
                                           pinnedAppsContainer.setVisibility(View.VISIBLE);
                                       }

                                       @Override
                                       public void onAnimationEnd(Animator animator) {
                                           super.onAnimationEnd(animator);
                                           pinnedAppsContainer.clearAnimation();
                                       }
                                   });
                break;
            case "hide_favourites":
                pinnedAppsContainer.animate()
                                   .translationY(pinnedAppsContainer.getHeight())
                                   .setInterpolator(new FastOutSlowInInterpolator())
                                   .setDuration(animateDuration)
                                   .setListener(new AnimatorListenerAdapter() {
                                       @Override
                                       public void onAnimationEnd(Animator animator) {
                                           super.onAnimationEnd(animator);
                                           pinnedAppsContainer.setVisibility(View.GONE);
                                       }
                                   });
                break;
        }
    }

    private void applyPrefToViews() {
        // Workaround v21+ statusbar transparency issue.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            ViewGroup.MarginLayoutParams homeParams = (ViewGroup.MarginLayoutParams) slidingHome.getLayoutParams();
            homeParams.topMargin = ViewUtils.getStatusBarHeight(getResources());
        }

        // Empty out margins if they are not needed.
        if (!PreferenceHelper.usesComfyPadding()) {
            ViewGroup.MarginLayoutParams searchParams = (ViewGroup.MarginLayoutParams) searchContainer
                    .getLayoutParams();
            ViewGroup.MarginLayoutParams listParams = (ViewGroup.MarginLayoutParams) appListContainer
                    .getLayoutParams();
            searchParams.setMargins(0, 0, 0, 0);
            listParams.setMargins(0, 0, 0, 0);
        }

        // Hide the favourites panel when user chooses to disable it or when there's nothing to show.
        if (!PreferenceHelper.isFavouritesEnabled() || pinnedAppsAdapter.isEmpty()) {
            pinnedAppsContainer.setVisibility(View.GONE);
            shouldShowFavourites = false;
        }

        // Switch on wallpaper shade.
        if (PreferenceHelper.useWallpaperShade()) {
            View wallpaperShade = findViewById(R.id.wallpaper_shade);
            // Tints the navigation bar with a semi-transparent shade.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setNavigationBarColor(
                        getResources().getColor(R.color.navigationBarShade));
            }
            wallpaperShade.setBackgroundResource(R.drawable.image_inner_shadow);
        }
    }

    // Load available preferences.
    private void loadPref(Boolean isInit) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        editPrefs = prefs.edit();

        PreferenceHelper.fetchPreference(prefs);

        if (isInit) {
            prefs.registerOnSharedPreferenceChangeListener(this);

            // Get a list of our hidden apps, default to null if there aren't any.
            excludedAppsList.addAll(prefs.getStringSet("hidden_apps", excludedAppsList));

            // Set the app theme!
            switch (PreferenceHelper.appTheme()) {
                default:
                case "light":
                    setTheme(R.style.AppTheme_NoActionBar);
                    break;
                case "dark":
                    setTheme(R.style.AppTheme_Gray_NoActionBar);
                    break;
                case "black":
                    setTheme(R.style.AppTheme_Dark_NoActionBar);
                    break;
            }
        }
    }

    private void registerPackageReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");
        BroadcastReceiver packageReceiver = new PackageChangesReceiver();
        this.registerReceiver(packageReceiver, intentFilter);
    }

    private void createAppMenu(View v, Boolean isPinned, final String packageName) {
        final Uri packageNameUri = Uri.parse("package:" + packageName);

        int position;
        if (isPinned) {
            PinnedAppDetail selectedPackage = new PinnedAppDetail(null, packageName);
            position = pinnedAppsAdapter.getGlobalPositionOf(selectedPackage);
        } else {
            AppDetail selectedPackage = new AppDetail(null, null, packageName, false);
            position = appsAdapter.getGlobalPositionOf(selectedPackage);
        }

        // Inflate the app menu.
        appMenu = new PopupMenu(MainActivity.this, v);
        appMenu.getMenuInflater().inflate(R.menu.menu_app, appMenu.getMenu());

        if (isPinned) {
            appMenu.getMenu().removeItem(R.id.action_pin);
            appMenu.getMenu().removeItem(R.id.action_hide);
        } else {
            // Don't show the 'pin' action when the app is already pinned.
            if (pinnedAppString.contains(packageName)) {
                appMenu.getMenu().removeItem(R.id.action_pin);
            }
            appMenu.getMenu().removeItem(R.id.action_unpin);
        }

        // Remove uninstall menu if the app is a system app.
        if (AppUtils.isSystemApp(manager, packageName)) {
            appMenu.getMenu().removeItem(R.id.action_uninstall);
        }

        appMenu.show();

        final int finalPosition = position;
        appMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.action_pin:
                        AppUtils.pinApp(manager, packageName, pinnedAppsAdapter, pinnedAppList);
                        pinnedAppString = pinnedAppString.concat(packageName + ";");
                        editPrefs.putString("pinned_apps_list", pinnedAppString).apply();
                        if (!PreferenceHelper.isFavouritesEnabled()) {
                            Toast.makeText(MainActivity.this, R.string.warn_pinning,
                                    Toast.LENGTH_SHORT).show();
                        }
                        if (PreferenceHelper.isFavouritesEnabled() && pinnedAppsAdapter.getItemCount() == 1) {
                            shouldShowFavourites = true;
                        }
                        break;
                    case R.id.action_unpin:
                        pinnedAppList.remove(pinnedAppsAdapter.getItem(finalPosition));
                        pinnedAppsAdapter.removeItem(finalPosition);
                        pinnedAppString = pinnedAppString.replace(packageName + ";", "");
                        editPrefs.putString("pinned_apps_list", pinnedAppString).apply();
                        if (pinnedAppsAdapter.isEmpty()) {
                            doThis("hide_favourites");
                        }
                        break;
                    case R.id.action_info:
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                packageNameUri));
                        break;
                    case R.id.action_uninstall:
                        startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageNameUri));
                        break;
                    case R.id.action_hide:
                        // Add the app's package name to the exclusion list.
                        excludedAppsList.add(packageName);
                        editPrefs.putStringSet("hidden_apps", excludedAppsList).apply();
                        // Reload the app list!
                        appsList.remove(appsAdapter.getItem(finalPosition));
                        appsAdapter.removeItem(finalPosition);
                        break;
                    default:
                        // There is nothing to do.
                        break;
                }
                return true;
            }
        });
    }

    private void addGestureListener() {
        // Handle touch events in touchReceiver.
        touchReceiver.setOnTouchListener(new OnTouchListener(this) {
            @Override
            public void onSwipeDown() {
                // Show the app panel.
                doThis("show_panel");
            }

            @Override
            public void onLongPress() {
                // Show context menu when touchReceiver is long pressed.
                touchReceiver.showContextMenu();
            }

            @Override
            public void onClick() {
                // Imitate sliding panel drag view behaviour; show the app panel on click.
                if (PreferenceHelper.allowTapToOpen()) {
                    doThis("show_panel");
                }
            }
        });
    }

    private void addSearchBarListener() {
        // Implement listener for the search bar.
        searchBar.addTextChangedListener(new TextWatcher() {
            String searchBarText, searchHint;
            Snackbar searchSnack = Snackbar.make(snackHolder, searchHint,
                    Snackbar.LENGTH_INDEFINITE);

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Fetch texts for the snackbar.
                searchBarText = searchBar.getText().toString().trim();
                searchHint = String.format(getResources().getString(R.string.search_web_hint),
                        searchBarText);

                // Begin filtering our list.
                if (appsAdapter.hasFinishedLoading()) {
                    appsAdapter.setFilter(searchBarText);
                    appsAdapter.filterItems();
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // No-op.
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Don't allow spamming of empty spaces.
                if (s.length() > 0 && s.charAt(0) == ' ') {
                    s.delete(0, 1);
                }

                if (s.length() == 0) {
                    // Scroll back down to the start of the list if search query is empty.
                    Utils.requireNonNull(appsRecyclerView.getLayoutManager())
                         .scrollToPosition(current_app_count);

                    // Dismiss the search snackbar.
                    searchSnack.dismiss();
                } else if (s.length() > 0 && PreferenceHelper.promptSearch()) {
                    // Update the snackbar text.
                    searchSnack.setText(searchHint);

                    // Prompt user if they want to search their query online.
                    searchSnack.setAction(R.string.search_web_button, new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Utils.openLink(MainActivity.this,
                                    PreferenceHelper.getSearchProvider() + searchBarText);
                        }
                    }).show();

                    // Disable search snackbar swipe-to-dismiss.
                    ViewUtils.disableSnackbarSwipe(searchSnack);
                }
            }
        });

        // Listen for keyboard enter/search key input.
        searchBar.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((!appsAdapter.isEmpty() && searchBar.getText().length() > 0) &&
                        (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL)) {
                    if (!appsRecyclerView.canScrollVertically(RecyclerView.FOCUS_UP)
                            && !appsRecyclerView.canScrollVertically(RecyclerView.FOCUS_DOWN)) {
                        launchApp(Utils.requireNonNull(
                                appsAdapter.getItem(appsAdapter.getItemCount() - 1))
                                       .getPackageName());
                    } else {
                        launchApp(Utils.requireNonNull(appsAdapter.getItem(0)).getPackageName());
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private void addListListeners() {
        // Listen for app list scroll to hide/show favourites panel.
        // Only do this when the user has favourites panel enabled.
        if (PreferenceHelper.isFavouritesEnabled()) {
            appsRecyclerView.addOnScrollListener(new SimpleScrollUpListener(0) {
                @Override
                public void onScrollUp() {
                    if (shouldShowFavourites && !pinnedAppsAdapter.isEmpty() && !PreferenceHelper.favouritesIgnoreScroll()) {
                        doThis("hide_favourites");
                    }
                }

                @Override
                public void onEnd() {
                    if (shouldShowFavourites && !pinnedAppsAdapter.isEmpty() && !PreferenceHelper.favouritesIgnoreScroll()) {
                        doThis("show_favourites");
                    }
                }
            });
        }

        // Add short click/click listener to the app list.
        RecyclerClick.addTo(appsRecyclerView)
                     .setOnItemClickListener(new RecyclerClick.OnItemClickListener() {
                         @Override
                         public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                             launchApp(Utils.requireNonNull(appsAdapter.getItem(position))
                                            .getPackageName());
                         }
                     });

        // Add long click action to app list. Long click shows a menu to manage selected app.
        RecyclerClick.addTo(appsRecyclerView)
                     .setOnItemLongClickListener(new RecyclerClick.OnItemLongClickListener() {
                         @Override
                         public boolean onItemLongClicked(RecyclerView recyclerView, final int position, View v) {
                             // Parse package URI for use in uninstallation and package info call.
                             final String packageName = Utils.requireNonNull(
                                     appsAdapter.getItem(position)).getPackageName();
                             createAppMenu(v, false, packageName);
                             return false;
                         }
                     });

        // Add long click action to pinned apps.
        RecyclerClick.addTo(pinnedAppsRecyclerView)
                     .setOnItemLongClickListener(new RecyclerClick.OnItemLongClickListener() {
                         @Override
                         public boolean onItemLongClicked(RecyclerView recyclerView, final int position, View v) {
                             // Parse package URI for use in uninstallation and package info call.
                             final String packageName = Utils.requireNonNull(
                                     pinnedAppsAdapter.getItem(position)).getPackageName();
                             createAppMenu(v, true, packageName);
                             return false;
                         }
                     });

        RecyclerClick.addTo(pinnedAppsRecyclerView)
                     .setOnItemClickListener(new RecyclerClick.OnItemClickListener() {
                         @Override
                         public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                             launchApp(Utils.requireNonNull(pinnedAppsAdapter.getItem(position))
                                            .getPackageName());
                         }
                     });
    }

    private void addPanelListener() {
        slidingHome.addPanelSlideListener(new SlidingUpPanelLayout.PanelSlideListener() {
            @Override public void onPanelSlide(View view, float v) {
                appsLayoutManager.setVerticalScrollEnabled(false);

                // Hide the keyboard at slide.
                ActivityServiceUtils.hideSoftKeyboard(MainActivity.this);

                // Dismiss any visible menu.
                if (appMenu != null) {
                    appMenu.dismiss();
                }
            }

            @Override public void onPanelStateChanged(View panel,
                    SlidingUpPanelLayout.PanelState previousState,
                    SlidingUpPanelLayout.PanelState newState) {
                if (newState == SlidingUpPanelLayout.PanelState.COLLAPSED
                        || newState == SlidingUpPanelLayout.PanelState.DRAGGING) {

                    if (newState != SlidingUpPanelLayout.PanelState.DRAGGING) {
                        appsLayoutManager.setVerticalScrollEnabled(true);
                    }

                    // Unregister context menu for touchReceiver as we don't want
                    // the user to accidentally show it during search.
                    unregisterForContextMenu(touchReceiver);

                    // Empty out search bar text
                    searchBar.setText(null);

                    // Automatically show keyboard when the panel is called.
                    if (PreferenceHelper.shouldFocusKeyboard()
                            && previousState != SlidingUpPanelLayout.PanelState.COLLAPSED) {
                        ActivityServiceUtils.showSoftKeyboard(MainActivity.this, searchBar);
                    }

                    // Animate search container entering the view.
                    searchContainer.animate().alpha(1f).setDuration(animateDuration)
                                   .setListener(new AnimatorListenerAdapter() {
                                       @Override
                                       public void onAnimationStart(Animator animation) {
                                           searchContainer.setVisibility(View.VISIBLE);
                                       }

                                       @Override
                                       public void onAnimationEnd(Animator animation) {
                                           searchContainer.clearAnimation();
                                       }
                                   });
                } else if (newState == SlidingUpPanelLayout.PanelState.EXPANDED) {
                    appsLayoutManager.setVerticalScrollEnabled(false);

                    // Re-register touchReceiver context menu.
                    registerForContextMenu(touchReceiver);

                    // Hide keyboard if container is invisible.
                    ActivityServiceUtils.hideSoftKeyboard(MainActivity.this);

                    // Stop scrolling, the panel is being dismissed.
                    appsRecyclerView.stopScroll();

                    searchContainer.setVisibility(View.INVISIBLE);

                    // Also animate the container when it's disappearing.
                    searchContainer.animate().alpha(0).setDuration(animateDuration)
                                   .setListener(new AnimatorListenerAdapter() {
                                       @Override
                                       public void onAnimationEnd(Animator animation) {
                                           searchContainer.clearAnimation();
                                       }
                                   });
                } else if (newState == SlidingUpPanelLayout.PanelState.ANCHORED) {
                    doThis("show_panel");
                }
            }
        });
    }

    private static class getAppTask extends AsyncTask<Void, Void, Void> {
        private WeakReference<MainActivity> activityRef;

        getAppTask(MainActivity context) {
            activityRef = new WeakReference<>(context);
        }

        @Override
        protected void onPreExecute() {
            MainActivity activity = activityRef.get();

            if (activity != null) {
                // Show the progress bar so the list wouldn't look empty.
                activity.loadProgress.setVisibility(View.VISIBLE);

                // Clear the list before populating.
                if (!activity.appsAdapter.isEmpty()) {
                    activity.appsList.clear();
                    activity.appsAdapter.clear();
                }
            }
        }

        @Override
        protected Void doInBackground(Void... params) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                activity.loadApps();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void results) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                // Remove the progress bar.
                activity.loadProgress.setVisibility(View.GONE);
                activity.loadProgress.invalidate();

                // Add the fetched apps and update item view cache.
                activity.appsAdapter.addItems(0, activity.appsList);
                activity.appsRecyclerView.setItemViewCacheSize(activity
                        .appsAdapter.getItemCount() - 1);

                activity.appsAdapter.finishedLoading(true);
            }
        }
    }
}
