package com.dkc.fileserverclient.backup

/**
 * 统一注册本应用所有需要备份的数据源。
 * 新增备份项只需在下面两个列表里加一行，或调用 BackupManager.register() 注册自定义 Exportable。
 */
object BuiltinBackups {

    /** 需要备份的 SharedPreferences 名称 */
    private val PREFS_TO_BACKUP = listOf(
        "FileServerPrefs",           // 连接历史、自动连接、渐变索引
        "albums_prefs",              // 相册集
        "playlist_prefs",            // 歌单
        "playlist_play_mode",        // 每个歌单的播放模式
        "audio_library_play_mode",   // 音频库播放模式
        "audio_library_animation",   // 音频库动画索引
        "playlist_detail_animation", // 歌单详情动画索引
        "audio_cache",               // 上次播放列表/索引/位置/时长、server_url
        "reading_prefs",             // 字体大小、背景色、语速、自动朗读开关
        "tts_prefs",                 // 选择的 TTS 引擎
        "reader_settings",           // ReadingSettings 备份
        "app_prefs",                 // 最近视频
        "video_library_prefs",       // 视频库视图模式
        "subtitle_prefs"             // 字幕样式
        // 说明：lyrics_files_cache 是短期缓存（10 分钟 TTL），不需要备份
    )

    /** 需要备份的 filesDir 子目录 */
    private val DIRS_TO_BACKUP = listOf(
        "reading_history",  // 文本阅读进度
        "audio_covers"      // 歌曲封面
        // 说明：thumbnails / coil_cache 都是 cacheDir，不备份
    )

    /** 在 Application.onCreate() 里调用一次 */
    fun registerAll() {
        PREFS_TO_BACKUP.forEach { BackupManager.register(PrefsExportable(it)) }
        DIRS_TO_BACKUP.forEach  { BackupManager.register(FileDirExportable(it)) }
    }
}