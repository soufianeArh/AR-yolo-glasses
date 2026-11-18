package com.soufiane.ar.glasses

/**
 * The app's languages: English and Chinese (setting "language").
 *
 * A plain Kotlin table instead of Android string resources, on purpose: the popup / verdict logic
 * runs in JVM unit tests where Android resources do not exist, and model labels ("Corn leaf blight")
 * need a lookup table anyway. Logcat lines stay in English (tests read them).
 */
object Lang {
    val CODES = listOf("en", "zh")
    val NAMES = listOf("English", "中文")

    @Volatile var current = "en"

    /** The UI text [key] in the current language, formatted with [args]. */
    fun t(key: String, vararg args: Any?): String {
        val table = if (current == "zh") ZH else EN
        val s = table[key] ?: EN[key] ?: key
        return if (args.isEmpty()) s else String.format(java.util.Locale.US, s, *args)
    }

    /** A model label (plant disease, COCO object) or crop name in the current language. */
    fun label(raw: String): String =
        if (current == "zh") LABELS_ZH[raw] ?: LABELS_ZH[raw.lowercase()] ?: raw
        else raw.replace('_', ' ')

    /** "corn, potato or tomato" / "玉米、马铃薯或番茄" */
    fun orList(items: List<String>): String {
        val l = items.map { label(it) }
        if (l.size < 2) return l.joinToString()
        return if (current == "zh") l.dropLast(1).joinToString("、") + "或" + l.last()
        else l.dropLast(1).joinToString(", ") + " or " + l.last()
    }

    private val EN = mapOf(
        // welcome
        "app_name" to "AR Plant Inspector",
        "tagline" to "Look at a corn, tomato or potato leaf and hold still:\nthe glasses tell you if it is diseased.",
        "server_checking" to "server %s: checking...",
        "server_ok" to "server %s: reachable ✓",
        "server_bad" to "server %s: NOT reachable ✗ (check Settings)",
        "demos_count" to "%d demo videos",
        "demos_offline" to " (demo server not reachable: only videos already on this device)",
        "btn_start_camera" to "Start inspection (camera)",
        "btn_demos" to "Demo videos",
        "btn_settings" to "Settings",
        // demo list
        "demos_title" to "Demo videos",
        "demos_loading" to "loading the list...",
        "demos_pick" to "Pick a video: it plays as if it were the camera, and the glasses inspect it.",
        "demos_server_down" to "Demo server not reachable - showing the videos already on this device. (%s)",
        "downloading" to "downloading %s: %d%% of %d MB",
        "starting" to "starting %s...",
        "could_not_get" to "could not get %s: %s",
        "objects" to "objects",
        "plant_inspection" to "plant inspection",
        "object_stream" to "object stream",
        "on_device" to "on this device",
        "tap_download" to "tap to download (%s MB)",
        "shows_expected" to "shows the expected answers",
        "other" to "other",
        // inspection screen
        "home" to "⌂  Home",
        "videos" to "▶  Videos",
        "camera" to "◉  Camera",
        "pause" to "❚❚  Pause",
        "play" to "▶  Play",
        "settings" to "⚙  Settings",
        "live_camera" to "Live camera",
        "video_prefix" to "Video: %s",
        "test_image" to "Test image",
        "loading_video" to "Loading video...  %d%%\n(first time only)",
        "paused" to "paused",
        "server_back" to "Server back",
        "no_connection" to "No connection to the server\nretrying...",
        "net_slow_reconnect" to "Network too slow\nreconnecting...",
        "reconnecting" to "reconnecting...",
        "no_server" to "no server - retrying",
        "checking" to "checking leaf... (%d/%d)",
        "video_not_found" to "video not found: %s",
        "camera_refused" to "camera permission refused - choose another source in settings",
        "backend_error" to "backend error: %s",
        // popup
        "not_sure" to "Not sure - look closer\n(%s %s)",
        "frames_agree" to "%d/%d frames agree",
        "expected" to "expected: %s",
        "healthy" to "Healthy leaf (%s)",
        "none" to "No leaf disease found",
        "unsure" to "Unsure: %s or %s?",
        "failed" to "Check failed - try again",
        "failed_note" to "%s - try again",
        "net_slow" to "Network too slow",
        "other_plant" to "Not a %s leaf",
        // settings
        "settings_title" to "AR Plant Inspector - settings",
        "f_language" to "Language / 语言",
        "f_host" to "Server (Java scheduler) address",
        "f_port" to "Server port",
        "f_api_port" to "Demo videos: control plane API port (same address)",
        "f_start_screen" to "App opens on: welcome = entry screen, inspect = straight into inspection (glasses)",
        "f_source" to "Camera source (or pick a video on the Demo videos screen)",
        "f_mode" to "Mode: inspect = plant look-and-hold, stream = every frame",
        "f_model" to "Stream mode model (inspect always uses plant)",
        "f_fps" to "Frames per second (1-30)",
        "f_votes" to "Inspect: frames voted per leaf (1-5)",
        "f_minconf" to "Popup minimum confidence 0-1 (empty = per model: plant 0.40, COCO 0.50)",
        "f_classes" to "Stream mode: classes allowed to pop up, comma separated (empty = all)",
        "f_crops" to "Inspect: crops to inspect, comma separated (empty = any plant)",
        "cb_show_camera" to "Show the camera image behind the popup (turn OFF on see-through glasses)",
        "cb_show_boxes" to "Show the detection boxes (and their labels) on the image",
        "cb_debug" to "Debug view (camera frame, boxes, stats - off on the glasses)",
        "btn_save" to "Save",
        "btn_cancel" to "Cancel",
    )

    private val ZH = mapOf(
        "app_name" to "AR 植物叶片检测",
        "tagline" to "看着玉米、番茄或马铃薯的叶片并保持不动：\n眼镜会告诉你它是否患病。",
        "server_checking" to "服务器 %s：检测中...",
        "server_ok" to "服务器 %s：已连接 ✓",
        "server_bad" to "服务器 %s：无法连接 ✗（请检查设置）",
        "demos_count" to "%d 个演示视频",
        "demos_offline" to "（演示服务器不可用：仅显示本设备上的视频）",
        "btn_start_camera" to "开始检测（摄像头）",
        "btn_demos" to "演示视频",
        "btn_settings" to "设置",
        "demos_title" to "演示视频",
        "demos_loading" to "正在加载列表...",
        "demos_pick" to "选择一个视频：它会像摄像头画面一样播放，并由眼镜进行检测。",
        "demos_server_down" to "演示服务器不可用，仅显示本设备上的视频。（%s）",
        "downloading" to "正在下载 %s：%d%%，共 %d MB",
        "starting" to "正在启动 %s...",
        "could_not_get" to "无法获取 %s：%s",
        "objects" to "物体",
        "plant_inspection" to "植物检测",
        "object_stream" to "物体识别",
        "on_device" to "已在本设备",
        "tap_download" to "点击下载（%s MB）",
        "shows_expected" to "显示正确答案",
        "other" to "其他",
        "home" to "⌂  主页",
        "videos" to "▶  视频",
        "camera" to "◉  摄像头",
        "pause" to "❚❚  暂停",
        "play" to "▶  继续",
        "settings" to "⚙  设置",
        "live_camera" to "实时摄像头",
        "video_prefix" to "视频：%s",
        "test_image" to "测试图片",
        "loading_video" to "正在加载视频...  %d%%\n（仅首次）",
        "paused" to "已暂停",
        "server_back" to "服务器已恢复",
        "no_connection" to "无法连接服务器\n正在重试...",
        "net_slow_reconnect" to "网络太慢\n正在重新连接...",
        "reconnecting" to "正在重新连接...",
        "no_server" to "无服务器 - 正在重试",
        "checking" to "正在检查叶片...（%d/%d）",
        "video_not_found" to "找不到视频：%s",
        "camera_refused" to "摄像头权限被拒绝 - 请在设置中选择其他来源",
        "backend_error" to "后端错误：%s",
        "not_sure" to "不确定 - 请靠近一点\n（%s %s）",
        "frames_agree" to "%d/%d 帧一致",
        "expected" to "正确答案：%s",
        "healthy" to "健康叶片（%s）",
        "none" to "未发现叶片病害",
        "unsure" to "不确定：%s 还是 %s？",
        "failed" to "检测失败 - 请重试",
        "failed_note" to "%s - 请重试",
        "net_slow" to "网络太慢",
        "other_plant" to "不是%s的叶片",
        "settings_title" to "AR 植物叶片检测 - 设置",
        "f_language" to "语言 / Language",
        "f_host" to "服务器（Java 调度器）地址",
        "f_port" to "服务器端口",
        "f_api_port" to "演示视频：控制平面 API 端口（同一地址）",
        "f_start_screen" to "启动时打开：welcome = 欢迎页，inspect = 直接开始检测（眼镜）",
        "f_source" to "画面来源（或在“演示视频”页面选择视频）",
        "f_mode" to "模式：inspect = 植物叶片注视检测，stream = 逐帧识别",
        "f_model" to "逐帧模式使用的模型（检测模式总是使用 plant）",
        "f_fps" to "每秒帧数（1-30）",
        "f_votes" to "检测：每片叶子投票的帧数（1-5）",
        "f_minconf" to "弹窗最低置信度 0-1（留空 = 按模型：植物 0.40，COCO 0.50）",
        "f_classes" to "逐帧模式：允许弹出的类别，逗号分隔（留空 = 全部）",
        "f_crops" to "检测：要检测的作物，逗号分隔（留空 = 任何植物）",
        "cb_show_camera" to "在弹窗后显示摄像头画面（透视眼镜请关闭）",
        "cb_show_boxes" to "在画面上显示检测框（及其标签）",
        "cb_debug" to "调试视图（画面、检测框、统计信息 - 眼镜上关闭）",
        "btn_save" to "保存",
        "btn_cancel" to "取消",
    )

    /** Model labels and crop names in Chinese: the plant model's 31 classes + crops + COCO's 80. */
    private val LABELS_ZH = mapOf(
        // crops (settings "crops")
        "corn" to "玉米", "tomato" to "番茄", "potato" to "马铃薯",
        // plant model (PlantDoc classes)
        "leaves" to "叶片",
        "Apple Scab Leaf" to "苹果黑星病", "Apple leaf" to "苹果叶", "Apple rust leaf" to "苹果锈病",
        "Bell_pepper leaf" to "甜椒叶", "Bell_pepper leaf spot" to "甜椒叶斑病", "Blueberry leaf" to "蓝莓叶",
        "Cherry leaf" to "樱桃叶", "Corn Gray leaf spot" to "玉米灰斑病", "Corn leaf blight" to "玉米大斑病",
        "Corn rust leaf" to "玉米锈病", "Peach leaf" to "桃叶", "Potato leaf" to "马铃薯叶",
        "Potato leaf early blight" to "马铃薯早疫病", "Potato leaf late blight" to "马铃薯晚疫病",
        "Raspberry leaf" to "树莓叶", "Soyabean leaf" to "大豆叶", "Soybean leaf" to "大豆叶",
        "Squash Powdery mildew leaf" to "南瓜白粉病", "Strawberry leaf" to "草莓叶",
        "Tomato Early blight leaf" to "番茄早疫病", "Tomato Septoria leaf spot" to "番茄斑枯病",
        "Tomato leaf" to "番茄叶", "Tomato leaf bacterial spot" to "番茄细菌性斑点病",
        "Tomato leaf late blight" to "番茄晚疫病", "Tomato leaf mosaic virus" to "番茄花叶病毒病",
        "Tomato leaf yellow virus" to "番茄黄化曲叶病毒病", "Tomato mold leaf" to "番茄叶霉病",
        "Tomato two spotted spider mites leaf" to "番茄二斑叶螨", "grape leaf" to "葡萄叶",
        "grape leaf black rot" to "葡萄黑腐病",
        // COCO (stream mode)
        "person" to "人", "bicycle" to "自行车", "car" to "汽车", "motorcycle" to "摩托车", "airplane" to "飞机",
        "bus" to "公交车", "train" to "火车", "truck" to "卡车", "boat" to "船", "traffic light" to "红绿灯",
        "fire hydrant" to "消防栓", "stop sign" to "停车标志", "parking meter" to "停车计时器", "bench" to "长椅",
        "bird" to "鸟", "cat" to "猫", "dog" to "狗", "horse" to "马", "sheep" to "羊", "cow" to "牛",
        "elephant" to "大象", "bear" to "熊", "zebra" to "斑马", "giraffe" to "长颈鹿", "backpack" to "背包",
        "umbrella" to "雨伞", "handbag" to "手提包", "tie" to "领带", "suitcase" to "行李箱", "frisbee" to "飞盘",
        "skis" to "滑雪板", "snowboard" to "单板滑雪板", "sports ball" to "球", "kite" to "风筝",
        "baseball bat" to "棒球棒", "baseball glove" to "棒球手套", "skateboard" to "滑板", "surfboard" to "冲浪板",
        "tennis racket" to "网球拍", "bottle" to "瓶子", "wine glass" to "酒杯", "cup" to "杯子", "fork" to "叉子",
        "knife" to "刀", "spoon" to "勺子", "bowl" to "碗", "banana" to "香蕉", "apple" to "苹果",
        "sandwich" to "三明治", "orange" to "橙子", "broccoli" to "西兰花", "carrot" to "胡萝卜", "hot dog" to "热狗",
        "pizza" to "披萨", "donut" to "甜甜圈", "cake" to "蛋糕", "chair" to "椅子", "couch" to "沙发",
        "potted plant" to "盆栽", "bed" to "床", "dining table" to "餐桌", "toilet" to "马桶", "tv" to "电视",
        "laptop" to "笔记本电脑", "mouse" to "鼠标", "remote" to "遥控器", "keyboard" to "键盘",
        "cell phone" to "手机", "microwave" to "微波炉", "oven" to "烤箱", "toaster" to "烤面包机", "sink" to "水槽",
        "refrigerator" to "冰箱", "book" to "书", "clock" to "时钟", "vase" to "花瓶", "scissors" to "剪刀",
        "teddy bear" to "泰迪熊", "hair drier" to "吹风机", "toothbrush" to "牙刷",
    )
}
