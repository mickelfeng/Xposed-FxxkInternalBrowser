# 说明

**警告：本模块处于开发阶段，仅在 Android 11 上通过测试，功能尚不完整，建议不要使用**

本模块为系统模块，作用域需选择「系统框架」。

模块的主要功能就是拦截 App 的「内置浏览器」打开网页的操作，并替换成用户自己的浏览器，类似「[External Link (去你大爷的内置浏览器)](https://github.com/xloger/ExLink)」，不过提供了询问功能，允许用户选择是否通过内置浏览器打开。

模块内置了 QQ 、微信、bilibili 的拦截逻辑。

# 原理

模块通过 hook ActivityTaskManagerService 的 startActivity 方法劫持内置浏览器的 Intent ，解析 Intent 并获取要打开的 url 。

## 解析 url

获取 url 主要通过：

1. Intent 的 Data Url （如 bilibili）  
2. Intent Extra （如 QQ ，微信）  

解析后者往往会出现很多问题，如 Intent 中包含了只有目标 app 才有的自定义 Parcelable ，在系统服务中解析会报错，并破坏原 Intent 的完整性。因此本模块直接将 Parcelable 进行 marshall ，在得到的 bytes 中搜索 extra 键值对以获取 url 。

## 弹窗询问

如果需要询问用户，则 startActivity 会直接返回 START_SUCCESS ，并由系统服务创建一个 AlertDialog 弹窗进行询问（[可以看看这个 gist](https://gist.github.com/5ec1cff/cc1f26d02a000d7d72d193ce032f59c5)）。如果用户选择用内置浏览器打开，会以系统权限重新执行 startActivity 。

# 优点

1. 不需要注入应用。  
2. 支持弹窗询问和设置域名偏好。  
3. 已实现动态配置（不完全，需要通过 json 手动配置）

# TODO

1. 更好的弹窗 UI 。  
2. 用户友好的配置界面。  
3. 支持更多 Intent 编辑功能。  
4. 非系统级 Xposed 实现。