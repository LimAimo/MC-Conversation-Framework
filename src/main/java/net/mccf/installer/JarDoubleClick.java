package net.mccf.installer;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * 双击 jar 文件时的入口类——弹一个安装提示窗口，告诉玩家这是 Minecraft 模组、
 * 需要放到 mods 文件夹、需要先装 Fabric Loader 和 Fabric API。
 *
 * 职责边界：
 * - 只管：双击 jar 时的弹窗提示和"打开教程"按钮
 * - 不管：游戏内任何逻辑（那是 net.mccf.mod.* 包的职责）、模组加载流程（那是 Fabric Loader 的职责）
 *
 * 为什么需要这个类：很多新玩家下载 jar 后会双击试图"安装"或"运行"，结果只看到
 * JVM 报错（如 "no main manifest attribute" 或 "could not find or load main class"），
 * 完全不知道该怎么办。一个友好的弹窗能省掉大量"我不会装"的咨询。
 *
 * 为什么不依赖 Minecraft/Fabric：双击 jar 时 JVM 直接调 main 方法，此时 Fabric Loader
 * 还没介入、Minecraft 类不在 classpath 上。这个类只能用 JDK 自带的 Swing/AWT。
 * Fabric Loader 通过 fabric.mod.json 识别 mod，完全不看 MANIFEST.MF 的 Main-Class，
 * 所以加这个入口不影响游戏内加载。
 *
 * 为什么翻译硬编码在 Map 里而不是读语言文件：assets/mccf/lang/*.json 是给 Minecraft
 * 的 Text.translatable() 用的，需要 Minecraft 的资源加载管线。双击 jar 时没有这条管线，
 * 只能自己管理翻译。硬编码 9 种语言 + 英文 fallback 覆盖项目已支持的所有语言。
 */
public class JarDoubleClick {

	/** GitHub 仓库地址，"打开教程"按钮指向这里。 */
	private static final String GITHUB_URL =
			"https://github.com/VirTuaL-EnTitY/MC-Conversation-Framework-1.21.1";

	/**
	 * 9 种语言的翻译文本。每种语言 4 个字符串：[标题, 正文, 打开教程按钮, 确定按钮]。
	 *
	 * key 用 Locale.toString() 的格式（如 "zh_CN"），中文区分简繁体；
	 * 其他语言只取语言部分（如 "ja"、"ko"），因为 toString() 可能是 "ja_JP" 但 key 用 "ja" 也能匹配。
	 * 匹配逻辑见 main 方法：先试 toString() 精确匹配，再试 getLanguage() 匹配语言部分，最后 fallback 到 "en"。
	 */
	private static final Map<String, String[]> TRANSLATIONS = Map.of(
			"en", new String[]{
					"MCCF Installation",
					"This is a Minecraft mod, not a standalone program.\n\n" +
							"To install:\n" +
							"1. Put this .jar into your Minecraft mods folder (.minecraft/mods)\n" +
							"2. Install Fabric Loader and Fabric API (required)\n" +
							"3. Launch Minecraft with the Fabric profile",
					"Open Tutorial",
					"OK"
			},
			"zh_CN", new String[]{
					"MCCF 安装提示",
					"这是一个 Minecraft 模组，不是独立程序。\n\n" +
							"安装方法：\n" +
							"1. 把这个 .jar 文件放到 Minecraft 的 mods 文件夹（.minecraft/mods）\n" +
							"2. 需要先安装 Fabric Loader 和 Fabric API（必需）\n" +
							"3. 用 Fabric 配置启动 Minecraft",
					"打开教程",
					"确定"
			},
			"zh_TW", new String[]{
					"MCCF 安裝提示",
					"這是一個 Minecraft 模組，不是獨立程式。\n\n" +
							"安裝方法：\n" +
							"1. 把這個 .jar 檔案放到 Minecraft 的 mods 資料夾（.minecraft/mods）\n" +
							"2. 需要先安裝 Fabric Loader 和 Fabric API（必需）\n" +
							"3. 用 Fabric 設定啟動 Minecraft",
					"打開教學",
					"確定"
			},
			"ja", new String[]{
					"MCCF インストール",
					"これは Minecraft のモッドであり、単体プログラムではありません。\n\n" +
							"インストール方法：\n" +
							"1. この .jar ファイルを Minecraft の mods フォルダ（.minecraft/mods）に置く\n" +
							"2. Fabric Loader と Fabric API を先にインストール（必須）\n" +
							"3. Fabric プロファイルで Minecraft を起動",
					"チュートリアルを開く",
					"OK"
			},
			"ko", new String[]{
					"MCCF 설치",
					"이것은 Minecraft 모드이며, 독립 실행 프로그램이 아닙니다.\n\n" +
							"설치 방법:\n" +
							"1. 이 .jar 파일을 Minecraft mods 폴더(.minecraft/mods)에 넣으세요\n" +
							"2. Fabric Loader와 Fabric API를 먼저 설치하세요(필수)\n" +
							"3. Fabric 프로필로 Minecraft를 실행하세요",
					"튜토리얼 열기",
					"확인"
			},
			"de", new String[]{
					"MCCF Installation",
					"Dies ist ein Minecraft-Mod, kein eigenständiges Programm.\n\n" +
							"Installation:\n" +
							"1. Legen Sie diese .jar-Datei in Ihren Minecraft mods-Ordner (.minecraft/mods)\n" +
							"2. Installieren Sie Fabric Loader und Fabric API (erforderlich)\n" +
							"3. Starten Sie Minecraft mit dem Fabric-Profil",
					"Anleitung öffnen",
					"OK"
			},
			"fr", new String[]{
					"Installation MCCF",
					"Ceci est un mod Minecraft, pas un programme autonome.\n\n" +
							"Installation :\n" +
							"1. Placez ce fichier .jar dans le dossier mods de Minecraft (.minecraft/mods)\n" +
							"2. Installez d'abord Fabric Loader et Fabric API (requis)\n" +
							"3. Lancez Minecraft avec le profil Fabric",
					"Ouvrir le tutoriel",
					"OK"
			},
			"es", new String[]{
					"Instalación de MCCF",
					"Esto es un mod de Minecraft, no un programa independiente.\n\n" +
							"Instalación:\n" +
							"1. Coloca este archivo .jar en la carpeta mods de Minecraft (.minecraft/mods)\n" +
							"2. Instala primero Fabric Loader y Fabric API (requerido)\n" +
							"3. Inicia Minecraft con el perfil de Fabric",
					"Abrir tutorial",
					"OK"
			},
			"ru", new String[]{
					"Установка MCCF",
					"Это мод для Minecraft, а не отдельная программа.\n\n" +
							"Установка:\n" +
							"1. Поместите этот .jar файл в папку mods Minecraft (.minecraft/mods)\n" +
							"2. Сначала установите Fabric Loader и Fabric API (обязательно)\n" +
							"3. Запустите Minecraft с профилем Fabric",
					"Открыть руководство",
					"OK"
			}
	);

	public static void main(String[] args) {
		// 无头环境（如服务器、CI）直接退出，不弹窗——Desktop.isDesktopSupported()
		// 在无头环境会返回 false，GraphicsEnvironment.headless 检查更可靠。
		if (GraphicsEnvironment.isHeadless()) {
			return;
		}

		// 用系统原生 look and feel，让弹窗看起来像原生应用而非 Java 默认的金属外观。
		// 玩家对"看起来像系统弹窗"的信任度高于"看起来像 Java 程序"——后者容易让人
		// 误以为是病毒或恶意软件。
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			// fallback 到默认 look and feel，不影响功能
		}

		String[] texts = resolveTranslation();

		// 弹窗用 showOptionDialog 而非 showMessageDialog——因为需要两个按钮
		// （"打开教程" + "确定"），showMessageDialog 只有一个按钮。
		// 按钮 options 直接用翻译后的字符串，JOptionPane 会按顺序展示。
		int result = JOptionPane.showOptionDialog(
				null,
				texts[1],
				texts[0],
				JOptionPane.YES_NO_OPTION,
				JOptionPane.INFORMATION_MESSAGE,
				null,
				new String[]{texts[2], texts[3]},
				texts[3]
		);

		// result == 0 对应第一个按钮（"打开教程"），result == 1 对应第二个按钮（"确定"）。
		// 点关闭按钮（X）时 result == JOptionPane.CLOSED_OPTION（-1），视为"确定"不打开浏览器。
		if (result == 0) {
			openTutorial();
		}
	}

	/**
	 * 按系统语言匹配翻译文本。
	 *
	 * 匹配顺序：
	 * 1. Locale.toString() 精确匹配（如 "zh_CN"、"zh_TW"）——中文需要区分简繁体
	 * 2. Locale.getLanguage() 匹配语言部分（如 "ja"、"ko"、"de"）——其他语言不分地区
	 * 3. fallback 到英文 "en"
	 */
	private static String[] resolveTranslation() {
		Locale locale = Locale.getDefault();
		String fullCode = locale.toString();
		if (TRANSLATIONS.containsKey(fullCode)) {
			return TRANSLATIONS.get(fullCode);
		}
		String langCode = locale.getLanguage();
		if (TRANSLATIONS.containsKey(langCode)) {
			return TRANSLATIONS.get(langCode);
		}
		return TRANSLATIONS.get("en");
	}

	/**
	 * 用系统默认浏览器打开 GitHub 教程页面。
	 *
	 * 用 Desktop.browse 而非 Minecraft 的 Util.getOperatingSystem().open()——
	 * 因为双击 jar 时没有 Minecraft 上下文，Util 类不在 classpath 上。
	 * Desktop API 是 JDK 自带的跨平台方案，在 Windows/macOS/Linux 都可用。
	 * 失败时静默忽略——玩家可以手动复制 GITHUB_URL 到浏览器。
	 */
	private static void openTutorial() {
		try {
			if (Desktop.isDesktopSupported()) {
				Desktop desktop = Desktop.getDesktop();
				if (desktop.isSupported(Desktop.Action.BROWSE)) {
					desktop.browse(new URI(GITHUB_URL));
				}
			}
		} catch (Exception e) {
			// 静默忽略——玩家可以手动复制 URL，弹窗文字里已有路径提示
		}
	}
}
