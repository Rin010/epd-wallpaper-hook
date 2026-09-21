package top.ximin.epdwallpaper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int STORAGE_PERMISSION_REQUEST = 100;
    private static final int IMPORT_IMAGES_REQUEST = 101;
    private static final int ITEMS_PER_PAGE = 4;

    private SharedPreferences preferences;
    private LinearLayout root;
    private int currentPage;
    private boolean importing;
    private final ArrayList<RadioButton> shutdownRadios = new ArrayList<>();
    private final ArrayList<RadioButton> rebootRadios = new ArrayList<>();

    private ArrayList<Uri> importQueue;
    private int importPosition;
    private int importSucceeded;
    private int importFailed;
    private String importLastError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = WallpaperConfig.preferences(this);
        WallpaperConfig.migrateUnifiedModel(this, preferences);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
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
        if (requestCode == STORAGE_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importLegacyDirectory();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_IMAGES_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        ArrayList<Uri> images = new ArrayList<>();
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
            beginImportQueue(images);
        }
    }

    private void rebuild() {
        root.removeAllViews();
        shutdownRadios.clear();
        rebootRadios.clear();

        LinearLayout heading = horizontal();
        TextView title = text("电纸书画面管理", 22, true);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text(importing ? "处理中…" : "16级灰度 · 点图预览", 13, false);
        state.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(state, wrapWrap());
        root.addView(heading, matchWrap());

        LinearLayout switches = horizontal();
        addSwitch(switches, "总开关", null, WallpaperConfig.isGlobalEnabled(preferences));
        addSwitch(switches, "锁屏", WallpaperConfig.LOCK,
                WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.LOCK));
        addSwitch(switches, "关机", WallpaperConfig.SHUTDOWN,
                WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.SHUTDOWN));
        addSwitch(switches, "重启", WallpaperConfig.REBOOT,
                WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.REBOOT));
        root.addView(switches, matchWrap());

        LinearLayout actions = horizontal();
        Button add = mainButton(importing ? "正在处理" : "导入图片");
        add.setEnabled(!importing);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openImagePicker();
            }
        });
        actions.addView(add, weighted());
        Button sync = mainButton("同步到系统");
        sync.setEnabled(!importing);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                syncSystemStore(true);
            }
        });
        actions.addView(sync, weighted());
        Button legacy = mainButton("迁移旧图库");
        legacy.setEnabled(!importing);
        legacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) {
                    importLegacyDirectory();
                } else {
                    requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
                            STORAGE_PERMISSION_REQUEST);
                }
            }
        });
        actions.addView(legacy, weighted());
        root.addView(actions, matchWrap());

        List<WallpaperItem> items = WallpaperItem.all(this);
        int pageCount = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = horizontal();
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int column = 0; column < 2; column++) {
                int index = currentPage * ITEMS_PER_PAGE + rowIndex * 2 + column;
                if (index < items.size()) {
                    row.addView(createCard(items.get(index)), cardWeight());
                } else {
                    View placeholder = new View(this);
                    row.addView(placeholder, cardWeight());
                }
            }
        }

        LinearLayout pager = horizontal();
        pager.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = mainButton("上一页");
        previous.setEnabled(currentPage > 0);
        previous.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentPage--;
                rebuild();
            }
        });
        pager.addView(previous, weighted());
        TextView page = text(String.format(Locale.CHINA, "第 %d / %d 页 · 共 %d 张",
                currentPage + 1, pageCount, items.size()), 14, true);
        page.setGravity(Gravity.CENTER);
        pager.addView(page, weighted(2f));
        Button next = mainButton("下一页");
        next.setEnabled(currentPage + 1 < pageCount);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                currentPage++;
                rebuild();
            }
        });
        pager.addView(next, weighted());
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private View createCard(final WallpaperItem item) {
        LinearLayout card = horizontal();
        card.setPadding(dp(7), dp(7), dp(7), dp(7));
        LinearLayout.LayoutParams own = cardWeight();
        own.setMargins(dp(3), dp(3), dp(3), dp(3));
        card.setLayoutParams(own);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF4F4F4);
        background.setStroke(dp(1), 0xFF666666);
        background.setCornerRadius(dp(3));
        card.setBackground(background);

        LinearLayout visual = new LinearLayout(this);
        visual.setOrientation(LinearLayout.VERTICAL);
        visual.setGravity(Gravity.CENTER);
        final WallpaperPreviewView preview = new WallpaperPreviewView(
                this, item, WallpaperConfig.getDateMode(preferences, item), false);
        preview.setContentDescription(item.title + "，单击全屏预览");
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFullscreenPreview(item);
            }
        });
        visual.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = text(item.title, 13, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        visual.addView(name, matchWrap());
        card.addView(visual, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.45f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), 0, 0, 0);
        card.addView(controls, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.55f));

        if (WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.LOCK)) {
            final CheckBox lock = new CheckBox(this);
            lock.setText("锁屏");
            lock.setTextSize(14);
            lock.setChecked(WallpaperConfig.isLockSelected(preferences, item));
            lock.setPadding(0, 0, 0, 0);
            lock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    WallpaperConfig.setLockSelected(preferences, item, isChecked);
                    SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
                }
            });
            controls.addView(lock, matchWrap());

            final Button date = compactButton(dateLabel(
                    WallpaperConfig.getDateMode(preferences, item)));
            date.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    String next = WallpaperConfig.nextDateMode(
                            WallpaperConfig.getDateMode(preferences, item));
                    WallpaperConfig.setDateMode(preferences, item, next);
                    date.setText(dateLabel(next));
                    preview.setDateMode(next);
                    SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
                }
            });
            controls.addView(date, wrapWrap());
        }

        if (WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.SHUTDOWN)) {
            RadioButton shutdown = roleRadio(item, WallpaperConfig.SHUTDOWN, "关机");
            shutdownRadios.add(shutdown);
            controls.addView(shutdown, matchWrap());
        }
        if (WallpaperConfig.isCategoryEnabled(preferences, WallpaperConfig.REBOOT)) {
            RadioButton reboot = roleRadio(item, WallpaperConfig.REBOOT, "重启");
            rebootRadios.add(reboot);
            controls.addView(reboot, matchWrap());
        }
        if (!item.system) {
            Button delete = compactButton("删除");
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmDelete(item.file);
                }
            });
            controls.addView(delete, wrapWrap());
        }
        return card;
    }

    private RadioButton roleRadio(final WallpaperItem item,
            final String category, String label) {
        RadioButton radio = new RadioButton(this);
        radio.setTag(item.id);
        radio.setText(label);
        radio.setTextSize(14);
        radio.setPadding(0, 0, 0, 0);
        radio.setChecked(item.id.equals(
                WallpaperConfig.getSelectedItem(preferences, category)));
        radio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                WallpaperConfig.setSelectedItem(preferences, category, item);
                ArrayList<RadioButton> radios = WallpaperConfig.SHUTDOWN.equals(category)
                        ? shutdownRadios : rebootRadios;
                for (RadioButton candidate : radios) {
                    candidate.setChecked(item.id.equals(candidate.getTag()));
                }
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
            }
        });
        return radio;
    }

    private void addSwitch(LinearLayout row, String label,
            final String category, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(14);
        control.setChecked(checked);
        control.setGravity(Gravity.CENTER);
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                String key = category == null
                        ? "global_enabled" : "category_enabled:" + category;
                preferences.edit().putBoolean(key, isChecked).commit();
                SystemWallpaperStore.syncConfigurationAsync(MainActivity.this, preferences);
                if (category != null) {
                    rebuild();
                }
            }
        });
        row.addView(control, weighted());
    }

    private void showFullscreenPreview(WallpaperItem item) {
        final Dialog dialog = new Dialog(this,
                android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        WallpaperPreviewView preview = new WallpaperPreviewView(
                this, item, WallpaperConfig.getDateMode(preferences, item), true);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.setContentView(preview, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        dialog.show();
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, IMPORT_IMAGES_REQUEST);
    }

    private void beginImportQueue(ArrayList<Uri> images) {
        if (importing) {
            return;
        }
        importing = true;
        importQueue = images;
        importPosition = 0;
        importSucceeded = 0;
        importFailed = 0;
        importLastError = null;
        rebuild();
        inspectNextImport();
    }

    private void inspectNextImport() {
        if (importQueue == null || importPosition >= importQueue.size()) {
            finishImportQueue();
            return;
        }
        final Uri uri = importQueue.get(importPosition);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ImageImporter.Inspection inspection =
                            ImageImporter.inspect(MainActivity.this, uri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (inspection.exactSize()) {
                                importCurrent(uri, inspection, ImageImporter.MODE_POINT_WHITE);
                            } else {
                                showAdaptationDialog(uri, inspection);
                            }
                        }
                    });
                } catch (final Throwable error) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            recordImportFailure(error);
                            importPosition++;
                            inspectNextImport();
                        }
                    });
                }
            }
        }, "EpdWallpaper-inspect").start();
    }

    private void showAdaptationDialog(final Uri uri,
            final ImageImporter.Inspection inspection) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<String> modes = new ArrayList<>();
        if (inspection.aspect == ImageImporter.ASPECT_WIDER) {
            labels.add("左右裁切（等比铺满屏幕）\n等比缩放到铺满屏幕，左右超出部分居中裁切");
            modes.add(ImageImporter.MODE_CROP);
            labels.add("上下填充黑（等比完整显示）\n等比缩放到完整显示，上下空白填黑");
            modes.add(ImageImporter.MODE_FIT_BLACK);
            labels.add("上下填充白（等比完整显示）\n等比缩放到完整显示，上下空白填白");
            modes.add(ImageImporter.MODE_FIT_WHITE);
        } else if (inspection.aspect == ImageImporter.ASPECT_TALLER) {
            labels.add("上下裁切（等比铺满屏幕）\n等比缩放到铺满屏幕，上下超出部分居中裁切");
            modes.add(ImageImporter.MODE_CROP);
            labels.add("左右填充黑（等比完整显示）\n等比缩放到完整显示，左右空白填黑");
            modes.add(ImageImporter.MODE_FIT_BLACK);
            labels.add("左右填充白（等比完整显示）\n等比缩放到完整显示，左右空白填白");
            modes.add(ImageImporter.MODE_FIT_WHITE);
        } else {
            String action = inspection.orientedWidth < inspection.targetWidth
                    ? "等比放大" : "等比缩小";
            labels.add(action + "至屏幕\n宽高比一致，不裁切、不填充、不做非等比变形");
            modes.add(ImageImporter.MODE_SCALE);
        }
        labels.add(pointDescription(inspection, "黑色"));
        modes.add(ImageImporter.MODE_POINT_BLACK);
        labels.add(pointDescription(inspection, "白色"));
        modes.add(ImageImporter.MODE_POINT_WHITE);

        final boolean[] resolved = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("适配图片 " + inspection.orientedWidth + "×"
                        + inspection.orientedHeight + " → 屏幕 "
                        + inspection.targetWidth + "×" + inspection.targetHeight)
                .setItems(labels.toArray(new String[labels.size()]),
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resolved[0] = true;
                        importCurrent(uri, inspection, modes.get(which));
                    }
                })
                .setNegativeButton("跳过这张", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resolved[0] = true;
                        importFailed++;
                        importPosition++;
                        inspectNextImport();
                    }
                })
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (!resolved[0]) {
                    resolved[0] = true;
                    importFailed++;
                    importPosition++;
                    inspectNextImport();
                }
            }
        });
        dialog.show();
    }

    private String pointDescription(ImageImporter.Inspection info, String color) {
        String behavior;
        if (info.orientedWidth <= info.targetWidth
                && info.orientedHeight <= info.targetHeight) {
            behavior = "原始像素不缩放，居中显示；尺寸不足处填充" + color;
        } else if (info.orientedWidth >= info.targetWidth
                && info.orientedHeight >= info.targetHeight) {
            behavior = "原始像素不缩放，超出屏幕的方向居中裁切；不足处填充" + color;
        } else {
            behavior = "原始像素不缩放；超出方向居中裁切，不足方向居中填充" + color;
        }
        return "居中显示（点对点，" + color + "填充）\n" + behavior;
    }

    private void importCurrent(final Uri uri, final ImageImporter.Inspection inspection,
            final String mode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ImageImporter.Result result = ImageImporter.importUri(
                            MainActivity.this, uri, inspection, mode);
                    WallpaperConfig.initializeImportedItem(preferences, result);
                    importSucceeded++;
                } catch (Throwable error) {
                    recordImportFailure(error);
                }
                importPosition++;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        inspectNextImport();
                    }
                });
            }
        }, "EpdWallpaper-import").start();
    }

    private void recordImportFailure(Throwable error) {
        importFailed++;
        importLastError = error.getMessage();
    }

    private void finishImportQueue() {
        final int succeeded = importSucceeded;
        final int failed = importFailed;
        final String lastError = importLastError;
        SystemWallpaperStore.syncAllAsync(this, preferences,
                new SystemWallpaperStore.Callback() {
            @Override
            public void onComplete(final boolean success, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        importing = false;
                        importQueue = null;
                        rebuild();
                        String result = "已导入 " + succeeded + " 张";
                        if (failed > 0) {
                            result += "，跳过/失败 " + failed + " 张";
                            if (lastError != null && lastError.length() > 0) {
                                result += "：" + lastError;
                            }
                        }
                        if (!success) {
                            result += "；系统同步失败：" + message;
                        }
                        Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
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
        } else {
            importLegacyDirectory();
        }
    }

    private void importLegacyDirectory() {
        if (importing) {
            return;
        }
        final File[] legacy = legacyImages();
        if (legacy.length == 0) {
            preferences.edit().putBoolean("legacy_migration_done", true).apply();
            Toast.makeText(this, "旧目录中没有可迁移图片", Toast.LENGTH_LONG).show();
            return;
        }
        importing = true;
        rebuild();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int succeeded = 0;
                String lastError = null;
                for (File source : legacy) {
                    try {
                        Uri uri = Uri.fromFile(source);
                        ImageImporter.Inspection inspection =
                                ImageImporter.inspect(MainActivity.this, uri);
                        String mode = inspection.exactSize()
                                ? ImageImporter.MODE_POINT_WHITE : ImageImporter.MODE_CROP;
                        ImageImporter.Result result = ImageImporter.importUri(
                                MainActivity.this, uri, inspection, mode);
                        WallpaperConfig.initializeMigratedItem(preferences, source, result);
                        succeeded++;
                    } catch (Throwable error) {
                        lastError = error.getMessage();
                    }
                }
                if (succeeded == legacy.length) {
                    preferences.edit().putBoolean("legacy_migration_done", true).commit();
                }
                importSucceeded = succeeded;
                importFailed = legacy.length - succeeded;
                importLastError = lastError;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishImportQueue();
                    }
                });
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

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("将从图库永久删除：\n" + file.getName())
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteImage(file);
                    }
                }).show();
    }

    private void deleteImage(File file) {
        try {
            WallpaperConfig.assertUnderRoot(this, file);
            if (file.isFile() && file.delete()) {
                WallpaperConfig.forgetCustomItem(preferences, file);
                SystemWallpaperStore.removeImageAsync(this, file, preferences);
                rebuild();
            } else {
                Toast.makeText(this, "删除失败", Toast.LENGTH_LONG).show();
            }
        } catch (IOException error) {
            Toast.makeText(this, "拒绝删除图库外文件", Toast.LENGTH_LONG).show();
        }
    }

    private String dateLabel(String mode) {
        mode = WallpaperConfig.normalizeDateMode(mode);
        if (WallpaperConfig.DATE_WHITE.equals(mode)) {
            return "日期：白";
        }
        if (WallpaperConfig.DATE_OFF.equals(mode)) {
            return "日期：关";
        }
        return "日期：黑";
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button compactButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), dp(3), dp(6), dp(3));
        return button;
    }

    private Button mainButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        return button;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams cardWeight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return weighted(1f);
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
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
