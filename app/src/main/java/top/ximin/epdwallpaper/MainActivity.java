package top.ximin.epdwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int STORAGE_PERMISSION_REQUEST = 100;
    private static final int IMPORT_IMAGES_REQUEST = 101;

    private SharedPreferences preferences;
    private LinearLayout content;
    private String pendingImportMode = ImageImporter.MODE_FILL;
    private boolean importing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = WallpaperConfig.preferences(this);

        ScrollView scrollView = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(24), dp(28), dp(40));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        rebuild();
        migrateLegacyAutomatically();
        if (!importing) {
            syncSystemStore(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                importLegacyDirectory();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_IMAGES_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        final ArrayList<Uri> images = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) {
                    images.add(uri);
                }
            }
        } else if (data.getData() != null) {
            images.add(data.getData());
        }
        if (!images.isEmpty()) {
            importImages(images, pendingImportMode);
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, STORAGE_PERMISSION_REQUEST);
        }
    }

    private void chooseImportMode() {
        new AlertDialog.Builder(this)
                .setTitle("图片如何适配屏幕？")
                .setItems(new String[] {
                        "铺满屏幕（推荐，居中裁剪边缘）",
                        "完整显示（不裁剪，空白处留白）"
                }, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingImportMode = which == 0
                                ? ImageImporter.MODE_FILL : ImageImporter.MODE_FIT;
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(intent, IMPORT_IMAGES_REQUEST);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importImages(final ArrayList<Uri> images, final String mode) {
        if (importing) {
            return;
        }
        importing = true;
        rebuild();
        Toast.makeText(this, "正在处理 " + images.size() + " 张图片…",
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int succeeded = 0;
                String lastError = null;
                for (Uri image : images) {
                    try {
                        ImageImporter.Result result =
                                ImageImporter.importUri(MainActivity.this, image, mode);
                        WallpaperConfig.initializeImportedItem(preferences, result);
                        succeeded++;
                    } catch (Throwable error) {
                        lastError = error.getMessage();
                    }
                }
                finishImport(succeeded, images.size() - succeeded, lastError);
            }
        }, "EpdWallpaper-import").start();
    }

    private void migrateLegacyAutomatically() {
        if (preferences.getBoolean("legacy_migration_done", false)
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        File[] legacy = legacyImages();
        if (legacy.length == 0) {
            preferences.edit().putBoolean("legacy_migration_done", true).apply();
            return;
        }
        importLegacyDirectory();
    }

    private void importLegacyDirectory() {
        if (importing) {
            return;
        }
        final File[] legacy = legacyImages();
        if (legacy.length == 0) {
            preferences.edit().putBoolean("legacy_migration_done", true).apply();
            Toast.makeText(this, "旧目录中没有可迁移的图片", Toast.LENGTH_LONG).show();
            return;
        }
        importing = true;
        rebuild();
        Toast.makeText(this, "正在迁移并适配 " + legacy.length + " 张旧图片…",
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int succeeded = 0;
                String lastError = null;
                for (File source : legacy) {
                    try {
                        ImageImporter.Result result = ImageImporter.importUri(
                                MainActivity.this, Uri.fromFile(source), ImageImporter.MODE_FILL);
                        WallpaperConfig.initializeMigratedItem(preferences, source, result);
                        succeeded++;
                    } catch (Throwable error) {
                        lastError = error.getMessage();
                    }
                }
                if (succeeded == legacy.length) {
                    preferences.edit().putBoolean("legacy_migration_done", true).commit();
                }
                finishImport(succeeded, legacy.length - succeeded, lastError);
            }
        }, "EpdWallpaper-migrate").start();
    }

    private File[] legacyImages() {
        if (!WallpaperConfig.LEGACY_ROOT.isDirectory()) {
            return new File[0];
        }
        File[] files = WallpaperConfig.LEGACY_ROOT.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.canRead()
                        && WallpaperConfig.isSupportedImage(file);
            }
        });
        return files == null ? new File[0] : files;
    }

    private void finishImport(final int succeeded, final int failed, final String lastError) {
        SystemWallpaperStore.syncAllAsync(this, preferences,
                new SystemWallpaperStore.Callback() {
            @Override
            public void onComplete(final boolean syncSuccess, final String syncMessage) {
                String message = "已导入 " + succeeded + " 张";
                if (failed > 0) {
                    message += "，失败 " + failed + " 张";
                    if (lastError != null && lastError.length() > 0) {
                        message += "：" + lastError;
                    }
                }
                if (!syncSuccess) {
                    message += "；尚未同步到系统：" + syncMessage;
                }
                final String completedMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        importing = false;
                        rebuild();
                        Toast.makeText(MainActivity.this,
                                completedMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void syncSystemStore(final boolean alwaysReport) {
        if (importing) {
            return;
        }
        importing = true;
        rebuild();
        SystemWallpaperStore.syncAllAsync(this, preferences,
                new SystemWallpaperStore.Callback() {
            @Override
            public void onComplete(final boolean success, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        importing = false;
                        rebuild();
                        if (alwaysReport || !success) {
                            Toast.makeText(MainActivity.this,
                                    success ? message : "请授予 Root 后重试：" + message,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private void rebuild() {
        content.removeAllViews();
        addTitle("电纸书画面管理", 30);
        addParagraph("自定义图片由应用安全保存在内部图库，不需要固定目录。每张图片可同时用于锁屏、关机和重启；已启用且勾选对应用途的图片会参与轮换。修改配置立即生效，无需重启。", 18);
        addParagraph("锁屏实际读取的是 /data/system/epd-wallpaper 中的系统副本；仅导入或改配置时使用 Root 同步，锁屏时不跨进程传图。", 16);

        Button sync = addButton(importing ? "正在同步…" : "立即同步到系统");
        sync.setEnabled(!importing);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                syncSystemStore(true);
            }
        });

        final Switch global = addSwitchRow(
                "启用画面替换", WallpaperConfig.isGlobalEnabled(preferences));
        global.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                preferences.edit().putBoolean("global_enabled", isChecked).commit();
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });

        addTitle("功能开关", 24);
        addCategorySwitch("锁屏画面", WallpaperConfig.LOCK);
        addCategorySwitch("关机画面", WallpaperConfig.SHUTDOWN);
        addCategorySwitch("重启画面", WallpaperConfig.REBOOT);

        addTitle("系统自带图片", 24);
        addParagraph("系统图片默认启用。它们只能禁用，不能删除。", 16);
        for (int index = 0; index < WallpaperConfig.LOCK_SYSTEM_IMAGES.length; index++) {
            String resource = WallpaperConfig.LOCK_SYSTEM_IMAGES[index];
            addSystemCard(WallpaperConfig.LOCK, resource,
                    "系统锁屏 " + (index + 1), resource, true);
        }
        addSystemCard(WallpaperConfig.SHUTDOWN, WallpaperConfig.SYSTEM_DEFAULT,
                "系统关机画面", systemPreviewResource(WallpaperConfig.SHUTDOWN), false);
        addSystemCard(WallpaperConfig.REBOOT, WallpaperConfig.SYSTEM_DEFAULT,
                "系统重启画面", systemPreviewResource(WallpaperConfig.REBOOT), false);

        addTitle("自定义图片", 24);
        DisplayMetrics display = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(display);
        int managedWidth = Math.min(display.widthPixels, display.heightPixels);
        int managedHeight = Math.max(display.widthPixels, display.heightPixels);
        addParagraph("点“导入图片”后可多选。应用会自动修正照片方向，并处理为本机屏幕 "
                + managedWidth + "×" + managedHeight + "；可选择铺满裁剪或完整留白。", 17);
        Button importButton = addButton(importing ? "正在导入…" : "导入图片");
        importButton.setEnabled(!importing);
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseImportMode();
            }
        });

        File[] files = WallpaperConfig.listImages(this);
        if (files.length == 0) {
            addParagraph("尚未导入自定义图片。支持系统文件选择器能够打开的 PNG、JPG、WEBP、BMP 等图片。", 17);
        } else {
            for (File file : files) {
                addCustomCard(file);
            }
        }

        Button legacy = addButton("迁移旧版 /sdcard/Wallpaper 图片（可选）");
        legacy.setEnabled(!importing);
        legacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) {
                    importLegacyDirectory();
                } else {
                    requestStoragePermissionIfNeeded();
                }
            }
        });
    }

    private void addCategorySwitch(String label, final String category) {
        Switch control = addSwitchRow(
                label, WallpaperConfig.isCategoryEnabled(preferences, category));
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                preferences.edit().putBoolean(
                        "category_enabled:" + category, isChecked).commit();
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });
    }

    private void addSystemCard(final String category, final String resourceName,
            String label, String previewResource, boolean supportsDate) {
        LinearLayout card = newCard();
        FrameLayout preview = createPreviewFrame();
        ImageView image = (ImageView) preview.getChildAt(0);
        setSystemPreview(image, previewResource);

        LinearLayout controls = newControlsColumn();
        controls.addView(text(label, 21, true));
        controls.addView(text("系统资源 · " + categoryLabel(category), 15, false));

        Switch enabled = new Switch(this);
        enabled.setText("启用并加入轮换");
        enabled.setTextSize(17);
        enabled.setChecked(WallpaperConfig.isSystemItemEnabled(
                preferences, category, resourceName));
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                WallpaperConfig.setSystemItemEnabled(
                        preferences, category, resourceName, isChecked);
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });
        controls.addView(enabled, matchWrap());

        if (supportsDate) {
            final TextView dateOverlay = (TextView) preview.getChildAt(1);
            CheckBox showDate = new CheckBox(this);
            showDate.setText("锁屏时显示日期");
            showDate.setTextSize(17);
            showDate.setChecked(WallpaperConfig.isSystemDateEnabled(
                    preferences, resourceName));
            dateOverlay.setVisibility(showDate.isChecked() ? View.VISIBLE : View.GONE);
            showDate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    WallpaperConfig.setSystemDateEnabled(
                            preferences, resourceName, isChecked);
                    SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
                    dateOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
            controls.addView(showDate, matchWrap());
        }

        TextView protectedLabel = text("系统图片不可删除", 15, false);
        protectedLabel.setTextColor(Color.DKGRAY);
        controls.addView(protectedLabel, matchWrap());
        card.addView(preview);
        card.addView(controls);
        content.addView(card, cardMargins());
    }

    private void addCustomCard(final File file) {
        LinearLayout card = newCard();
        final FrameLayout preview = createPreviewFrame();
        ImageView image = (ImageView) preview.getChildAt(0);
        image.setImageBitmap(loadThumbnail(file, 500, 650));

        LinearLayout controls = newControlsColumn();
        controls.addView(text(file.getName(), 21, true));
        TextView details = text(WallpaperConfig.getCustomInfo(preferences, file), 14, false);
        details.setTextColor(Color.DKGRAY);
        controls.addView(details);

        Switch enabled = new Switch(this);
        enabled.setText("启用这张图片");
        enabled.setTextSize(17);
        enabled.setChecked(WallpaperConfig.isCustomItemEnabled(preferences, file));
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                WallpaperConfig.setCustomItemEnabled(preferences, file, isChecked);
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });
        controls.addView(enabled, matchWrap());

        LinearLayout roles = new LinearLayout(this);
        roles.setOrientation(LinearLayout.HORIZONTAL);
        roles.addView(roleCheckBox(file, WallpaperConfig.LOCK, "锁屏"));
        roles.addView(roleCheckBox(file, WallpaperConfig.SHUTDOWN, "关机"));
        roles.addView(roleCheckBox(file, WallpaperConfig.REBOOT, "重启"));
        controls.addView(roles, matchWrap());

        final TextView dateOverlay = (TextView) preview.getChildAt(1);
        CheckBox showDate = new CheckBox(this);
        showDate.setText("用作锁屏时显示日期");
        showDate.setTextSize(17);
        showDate.setChecked(WallpaperConfig.isCustomDateEnabled(preferences, file));
        dateOverlay.setVisibility(showDate.isChecked() ? View.VISIBLE : View.GONE);
        showDate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                WallpaperConfig.setCustomDateEnabled(preferences, file, isChecked);
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
                dateOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });
        controls.addView(showDate, matchWrap());

        Button delete = new Button(this);
        delete.setText("删除这张图片");
        delete.setTextSize(16);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDelete(file);
            }
        });
        controls.addView(delete, wrapWrap());

        card.addView(preview);
        card.addView(controls);
        content.addView(card, cardMargins());
    }

    private CheckBox roleCheckBox(final File file, final String category, String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextSize(16);
        checkBox.setChecked(WallpaperConfig.isCustomRoleEnabled(
                preferences, file, category));
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                WallpaperConfig.setCustomRoleEnabled(
                        preferences, file, category, isChecked);
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });
        return checkBox;
    }

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("将从应用内图库永久删除：\n" + file.getName())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteImage(file);
                    }
                })
                .show();
    }

    private void deleteImage(File file) {
        try {
            WallpaperConfig.assertUnderRoot(this, file);
            if (file.isFile() && file.delete()) {
                WallpaperConfig.forgetCustomItem(preferences, file);
                SystemWallpaperStore.removeImageAsync(this, file, preferences);
                Toast.makeText(this, "已删除 " + file.getName(), Toast.LENGTH_SHORT).show();
                rebuild();
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_LONG).show();
            }
        } catch (IOException error) {
            Toast.makeText(this, "拒绝删除目录外文件", Toast.LENGTH_LONG).show();
        }
    }

    private FrameLayout createPreviewFrame() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(dp(220), dp(294));
        frameParams.setMargins(0, 0, dp(22), 0);
        frame.setLayoutParams(frameParams);
        frame.setBackgroundColor(Color.WHITE);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView date = text(new SimpleDateFormat(
                "MM月dd日  E", Locale.CHINA).format(new Date()), 16, true);
        date.setTextColor(Color.BLACK);
        date.setBackgroundColor(0xCCFFFFFF);
        date.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dateParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.BOTTOM);
        frame.addView(date, dateParams);
        return frame;
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setGravity(Gravity.TOP);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF2F2F2);
        background.setStroke(dp(1), 0xFF777777);
        background.setCornerRadius(dp(4));
        card.setBackground(background);
        return card;
    }

    private LinearLayout newControlsColumn() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(4), 0, 0, 0);
        controls.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return controls;
    }

    private Switch addSwitchRow(String label, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(20);
        control.setChecked(checked);
        control.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return control;
    }

    private Button addButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        LinearLayout.LayoutParams params = wrapWrap();
        params.setMargins(0, dp(12), 0, dp(12));
        content.addView(button, params);
        return button;
    }

    private void addTitle(String title, int size) {
        TextView view = text(title, size, true);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(16), 0, dp(10));
        content.addView(view, params);
    }

    private void addParagraph(String value, int size) {
        TextView view = text(value, size, false);
        view.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(14));
        content.addView(view, params);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private void setSystemPreview(ImageView image, String resourceName) {
        Resources resources = Resources.getSystem();
        int resourceId = resources.getIdentifier(resourceName, "drawable", "android");
        if (resourceId == 0) {
            image.setBackgroundColor(0xFFDDDDDD);
            return;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        image.setImageBitmap(BitmapFactory.decodeResource(resources, resourceId, options));
    }

    private String systemPreviewResource(String category) {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (WallpaperConfig.REBOOT.equals(category)) {
            return landscape ? "reboot_window_img_land" : "reboot_window_img";
        }
        return landscape ? "shutdown_window_img_land" : "shutdown_window_img";
    }

    private Bitmap loadThumbnail(File file, int targetWidth, int targetHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private String categoryLabel(String category) {
        if (WallpaperConfig.LOCK.equals(category)) {
            return "锁屏";
        }
        if (WallpaperConfig.SHUTDOWN.equals(category)) {
            return "关机";
        }
        return "重启";
    }

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
