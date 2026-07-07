    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
        });
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
        });
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
        });

        // ========== 直播源切换（带删除按钮） ==========
        tv_multi_source.setOnClickListener(v -> {
            SourceManager sourceManager = new SourceManager(this, "live_history");
            List<SourceManager.SourceItem> sources = sourceManager.getAllSources();
            if (sources.isEmpty()) {
                Toast.makeText(this, "暂未保存任何直播源，请先通过自定义订阅源添加", Toast.LENGTH_SHORT).show();
                return;
            }

            // 1. 创建自定义布局
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            ListView listView = new ListView(this);
            layout.addView(listView);

            // 2. 自定义适配器
            SwitchSourceAdapter adapter = new SwitchSourceAdapter(this, sources);
            int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
            adapter.setSelectedPosition(currentDefault);

            // 3. 设置操作回调（删除 或 切换）
            adapter.setOnDeleteClickListener(position -> {
                if (position == -1) {
                    // 切换操作：获取选中的索引，设为默认，并刷新
                    int selectedIdx = adapter.getSelectedPosition();
                    if (selectedIdx >= 0) {
                        sourceManager.setDefault(selectedIdx);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        Toast.makeText(this, "已切换到：" + sources.get(selectedIdx).name, Toast.LENGTH_SHORT).show();
                        // 重新触发点击事件来刷新弹窗
                        tv_multi_source.performClick();
                    }
                } else {
                    // 删除操作
                    SourceManager.SourceItem item = sources.get(position);
                    new AlertDialog.Builder(this)
                            .setTitle("确认删除")
                            .setMessage("确定要删除「" + item.name + "」吗？")
                            .setPositiveButton("删除", (d, w) -> {
                                sourceManager.removeSource(sourceManager.indexOfUrl(item.url));
                                // 删除后重新触发点击事件来刷新弹窗
                                tv_multi_source.performClick();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });

            listView.setAdapter(adapter);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                adapter.setSelectedPosition(position);
                // 触发切换
                adapter.setOnDeleteClickListener(-1);
            });

            // 4. 构建弹窗
            new AlertDialog.Builder(this)
                    .setTitle("切换直播源")
                    .setView(layout)
                    .setNegativeButton("取消", null)
                    .show();
        });

        // ========== 节目单切换（带删除按钮） ==========
        tv_multi_epg.setOnClickListener(v -> {
            SourceManager sourceManager = new SourceManager(this, "epg_history");
            List<SourceManager.SourceItem> sources = sourceManager.getAllSources();
            if (sources.isEmpty()) {
                Toast.makeText(this, "暂未保存任何节目单，请先通过自定义节目单添加", Toast.LENGTH_SHORT).show();
                return;
            }

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            ListView listView = new ListView(this);
            layout.addView(listView);

            SwitchSourceAdapter adapter = new SwitchSourceAdapter(this, sources);
            int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
            adapter.setSelectedPosition(currentDefault);

            adapter.setOnDeleteClickListener(position -> {
                if (position == -1) {
                    int selectedIdx = adapter.getSelectedPosition();
                    if (selectedIdx >= 0) {
                        sourceManager.setDefault(selectedIdx);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        Toast.makeText(this, "已切换到：" + sources.get(selectedIdx).name, Toast.LENGTH_SHORT).show();
                        tv_multi_epg.performClick();
                    }
                } else {
                    SourceManager.SourceItem item = sources.get(position);
                    new AlertDialog.Builder(this)
                            .setTitle("确认删除")
                            .setMessage("确定要删除「" + item.name + "」吗？")
                            .setPositiveButton("删除", (d, w) -> {
                                sourceManager.removeSource(sourceManager.indexOfUrl(item.url));
                                tv_multi_epg.performClick();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });

            listView.setAdapter(adapter);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                adapter.setSelectedPosition(position);
                adapter.setOnDeleteClickListener(-1);
            });

            new AlertDialog.Builder(this)
                    .setTitle("切换节目单")
                    .setView(layout)
                    .setNegativeButton("取消", null)
                    .show();
        });

        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
        });
    }
