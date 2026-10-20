--做事交给子代理做，主会话负责检查、整理
--此工程为nfc碰一碰传输工程，参考成熟产品Temp/video.mp4实现
--调试相册代码Temp\debuggallery
--截屏、悬浮窗控制参考了超级截屏mtk_x\pri_project\android\os_app_src\LongShotScreen进行实现
--后台服务、保活、开机启动打算参考mtk_x\pri_project\android\os_app_src\PriDynamicWindow灵动岛进行实现
--图片查看器参考mtk_x\pri_project\android\os_app_src\PriFileManagement文件管理进行实现
--测试以热点连接为主：真实用户双方不在同一个 LAN，热点是主要使用场景；同 LAN 只是碰巧，不能只测同 LAN 直连
--用户大概率会使用的场景：
  #1 A 有 1 张 / B 0 张 / A 先上滑
  #2 A 0 张 / B 1 张 / B 先上滑（#1 的对称）
  #3 A 3 张 / B 1 张 / 任意一方先上滑（张数不等）
  #4 A 2 张 / B 2 张 / 任意一方先上滑（张数相等）
  #5 A N 张 / B M 张 / 任意一方先上滑（任意不等）
  #6 A 有图 / B 有图 / 双方都上滑（同时上滑后退出）
  #7 A 任意 / B 任意 / 没人上滑（保持连接不动的死锁场景，必须能靠任一端退出解开）
  #8 A 0 张 / B 0 张 / 不碰触/不启动（已有行为：双方都没图时什么都不做）