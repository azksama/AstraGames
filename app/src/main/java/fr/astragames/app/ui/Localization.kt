package fr.astragames.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import fr.astragames.app.settings.AppLanguage

val LocalAppLanguage = compositionLocalOf { AppLanguage.ENGLISH }

/**
 * Small in-app catalogue so the language can change immediately without restarting the activity.
 * French remains the source language; unknown user data is always kept untouched.
 */
object AppLocalizer {
    @Volatile
    var language: AppLanguage = AppLanguage.ENGLISH

    private data class Translation(
        val source: String,
        val english: String,
        val spanish: String,
        val russian: String,
        val german: String,
        val chinese: String,
        val japanese: String
    ) {
        fun value(language: AppLanguage): String = when (language) {
            AppLanguage.ENGLISH -> english
            AppLanguage.FRENCH -> source
            AppLanguage.SPANISH -> spanish
            AppLanguage.RUSSIAN -> russian
            AppLanguage.GERMAN -> german
            AppLanguage.CHINESE -> chinese
            AppLanguage.JAPANESE -> japanese
        }
    }

    private fun t(
        source: String,
        english: String,
        spanish: String,
        russian: String,
        german: String,
        chinese: String,
        japanese: String
    ) = Translation(source, english, spanish, russian, german, chinese, japanese)

    private val catalog = listOf(
        t("Fichiers", "Files", "Archivos", "Файлы", "Dateien", "文件", "ファイル"),
        t("Textes uniques", "Unique texts", "Textos únicos", "Уникальные тексты", "Eindeutige Texte", "唯一文本", "重複を除くテキスト"),
        t("Caractères", "Characters", "Caracteres", "Символы", "Zeichen", "字符", "文字数"),
        t("Traduire le jeu", "Translate game", "Traducir el juego", "Перевести игру", "Spiel übersetzen", "翻译游戏", "ゲームを翻訳"),
        t("Traduction locale sans compte — RPG Maker MV/MZ", "Local translation without an account — RPG Maker MV/MZ", "Traducción local sin cuenta — RPG Maker MV/MZ", "Локальный перевод без аккаунта — RPG Maker MV/MZ", "Lokale Übersetzung ohne Konto — RPG Maker MV/MZ", "无需账号的本地翻译 — RPG Maker MV/MZ", "アカウント不要のローカル翻訳 — RPG Maker MV/MZ"),
        t("Langue du jeu", "Game language", "Idioma del juego", "Язык игры", "Spielsprache", "游戏语言", "ゲームの言語"),
        t("Langue de traduction", "Target language", "Idioma de destino", "Язык перевода", "Zielsprache", "目标语言", "翻訳先の言語"),
        t("Télécharger les langues uniquement en Wi-Fi", "Download languages over Wi-Fi only", "Descargar idiomas solo por Wi-Fi", "Загружать языки только по Wi-Fi", "Sprachen nur über WLAN herunterladen", "仅通过 Wi-Fi 下载语言", "Wi-Fi のみで言語をダウンロード"),
        t("Traduire avec Google", "Translate with Google", "Traducir con Google", "Перевести с Google", "Mit Google übersetzen", "使用 Google 翻译", "Google で翻訳"),
        t("Restaurer les originaux", "Restore originals", "Restaurar originales", "Восстановить оригиналы", "Originale wiederherstellen", "恢复原始文件", "元のファイルに戻す"),
        t("Traduction appliquée", "Translation applied", "Traducción aplicada", "Перевод применён", "Übersetzung angewendet", "已应用翻译", "翻訳を適用しました"),
        t("Aucun texte modifié", "No text changed", "Ningún texto modificado", "Текст не изменён", "Kein Text geändert", "文本未更改", "テキストの変更はありません"),
        t("Originaux restaurés", "Originals restored", "Originales restaurados", "Оригиналы восстановлены", "Originale wiederhergestellt", "已恢复原始文件", "元のファイルを復元しました"),
        t("Analyse des textes", "Analyzing text", "Analizando textos", "Анализ текста", "Texte werden analysiert", "正在分析文本", "テキストを解析中"),
        t("Téléchargement des langues", "Downloading languages", "Descargando idiomas", "Загрузка языков", "Sprachen werden heruntergeladen", "正在下载语言", "言語をダウンロード中"),
        t("Traduction locale", "Translating on device", "Traduciendo en el dispositivo", "Локальный перевод", "Lokale Übersetzung", "正在本地翻译", "デバイス上で翻訳中"),
        t("Sauvegarde et application", "Backing up and applying", "Guardando y aplicando", "Резервное копирование и применение", "Sichern und anwenden", "正在备份并应用", "バックアップと適用中"),
        t("Restauration des originaux", "Restoring originals", "Restaurando originales", "Восстановление оригиналов", "Originale werden wiederhergestellt", "正在恢复原始文件", "元のファイルを復元中"),
        t("À propos de Google Translate", "About Google Translate", "Acerca de Google Translate", "О Google Translate", "Über Google Translate", "关于 Google Translate", "Google Translate について"),
        t("Traduire le jeu ?", "Translate this game?", "¿Traducir este juego?", "Перевести эту игру?", "Dieses Spiel übersetzen?", "翻译此游戏？", "このゲームを翻訳しますか？"),
        t("Google Translate (ML Kit) traduit sur cet appareil, sans compte ni clé API.", "Google Translate (ML Kit) translates on this device, without an account or API key.", "Google Translate (ML Kit) traduce en este dispositivo, sin cuenta ni clave API.", "Google Translate (ML Kit) переводит на устройстве без аккаунта и ключа API.", "Google Translate (ML Kit) übersetzt auf diesem Gerät, ohne Konto oder API-Schlüssel.", "Google Translate (ML Kit) 在此设备上翻译，无需账号或 API 密钥。", "Google Translate (ML Kit) はこの端末で翻訳します。アカウントや API キーは不要です。"),
        t("Environ 30 Mo par langue au premier usage. Gardez Astra ouvert.", "About 30 MB per language on first use. Keep Astra open.", "Unos 30 MB por idioma la primera vez. Mantén Astra abierto.", "Около 30 МБ на язык при первом использовании. Не закрывайте Astra.", "Etwa 30 MB pro Sprache beim ersten Gebrauch. Astra geöffnet lassen.", "首次使用每种语言约需 30 MB。请保持 Astra 打开。", "初回は各言語約 30 MB をダウンロードします。Astra を開いたままにしてください。"),
        t("Originaux sauvegardés. Restaurez-les avant de retraduire ou de modifier les mods.", "Originals backed up. Restore them before translating again or changing mods.", "Originales guardados. Restáuralos antes de volver a traducir o cambiar mods.", "Оригиналы сохранены. Восстановите их перед новым переводом или изменением модов.", "Originale gesichert. Vor erneuter Übersetzung oder Mod-Änderungen wiederherstellen.", "已备份原始文件。重新翻译或修改模组前请先恢复。", "元のファイルを保存済みです。再翻訳や MOD 変更前に復元してください。"),
        t("MV/MZ : dialogues, choix et menus standards. Images, plugins et sauvegardes exclus.", "MV/MZ: standard dialogue, choices and menus. Images, plugins and saves excluded.", "MV/MZ: diálogos, opciones y menús estándar. Se excluyen imágenes, plugins y partidas.", "MV/MZ: стандартные диалоги, выборы и меню. Без изображений, плагинов и сохранений.", "MV/MZ: Standarddialoge, Auswahl und Menüs. Bilder, Plugins und Spielstände ausgenommen.", "MV/MZ：标准对话、选项和菜单。不包括图片、插件和存档。", "MV/MZ：標準の会話・選択肢・メニュー。画像・プラグイン・セーブは対象外です。"),
        t("Originaux : data/.astra-translation. Conservez ce dossier.", "Originals: data/.astra-translation. Keep this folder.", "Originales: data/.astra-translation. Conserva esta carpeta.", "Оригиналы: data/.astra-translation. Сохраните эту папку.", "Originale: data/.astra-translation. Diesen Ordner aufbewahren.", "原始文件：data/.astra-translation。请保留此文件夹。", "元のファイル：data/.astra-translation。このフォルダーを保存してください。"),
        t("Fermez le jeu avant de continuer. Une traduction automatique sera appliquée avec sauvegarde des originaux. Vous pourrez la restaurer depuis cet écran.", "Close the game first. Automatic translation will be applied after backing up the originals. You can restore them from this screen.", "Cierra el juego. Se aplicará una traducción automática tras guardar los originales. Podrás restaurarlos desde esta pantalla.", "Сначала закройте игру. Перевод будет применён после резервного копирования. Оригиналы можно восстановить на этом экране.", "Zuerst das Spiel schließen. Die Übersetzung wird nach der Sicherung angewendet. Originale lassen sich hier wiederherstellen.", "请先关闭游戏。备份原始文件后将应用自动翻译。您可以在此页面恢复。", "先にゲームを閉じてください。元のファイルを保存してから自動翻訳を適用します。この画面から復元できます。"),
        t("Interrompu. Les traductions en cache seront réutilisées.", "Stopped. Cached translations will be reused.", "Detenido. Se reutilizarán las traducciones en caché.", "Остановлено. Сохранённые переводы будут использованы повторно.", "Gestoppt. Gespeicherte Übersetzungen werden wiederverwendet.", "已停止。缓存翻译将被重复使用。", "中断しました。保存済みの翻訳は再利用されます。"),
        t("Effacer la recherche", "Clear search", "Borrar búsqueda", "Очистить поиск", "Suche löschen", "清除搜索", "検索をクリア"),
        t("Recherche en cours…", "Searching…", "Buscando…", "Поиск…", "Suche läuft…", "搜索中…", "検索中…"),
        t("Manquant", "Missing", "No encontrado", "Не найдено", "Fehlt", "缺失", "見つかりません"),
        t("Affichage", "Display", "Vista", "Отображение", "Ansicht", "显示", "表示"),
        t("Liste", "List", "Lista", "Список", "Liste", "列表", "リスト"),
        t("Jamais scannée", "Never scanned", "Nunca escaneada", "Сканирование не проводилось", "Noch nie gescannt", "尚未扫描", "未スキャン"),
        t("Scan en cours…", "Scanning…", "Escaneando…", "Сканирование…", "Scan läuft…", "扫描中…", "スキャン中…"),
        t("Scan terminé", "Scan complete", "Escaneo completado", "Сканирование завершено", "Scan abgeschlossen", "扫描完成", "スキャン完了"),
        t("Scan partiel", "Partial scan", "Escaneo parcial", "Частичное сканирование", "Teilweiser Scan", "部分扫描", "部分スキャン"),
        t("Échec du scan", "Scan failed", "Error al escanear", "Ошибка сканирования", "Scan fehlgeschlagen", "扫描失败", "スキャン失敗"),
        t("Bibliothèque", "Library", "Biblioteca", "Библиотека", "Bibliothek", "资料库", "ライブラリ"),
        t("Recherche", "Search", "Buscar", "Поиск", "Suche", "搜索", "検索"),
        t("Collections", "Collections", "Colecciones", "Коллекции", "Sammlungen", "收藏", "コレクション"),
        t("Tags", "Tags", "Etiquetas", "Теги", "Tags", "标签", "タグ"),
        t("Paramètres", "Settings", "Ajustes", "Настройки", "Einstellungen", "设置", "設定"),
        t("Retour", "Back", "Atrás", "Назад", "Zurück", "返回", "戻る"),
        t("Ouvrir", "Open", "Abrir", "Открыть", "Öffnen", "打开", "開く"),
        t("Rechercher", "Search", "Buscar", "Поиск", "Suchen", "検索", "検索"),
        t("Fermer", "Close", "Cerrar", "Закрыть", "Schließen", "关闭", "閉じる"),
        t("Annuler", "Cancel", "Cancelar", "Отмена", "Abbrechen", "取消", "キャンセル"),
        t("Valider", "Done", "Listo", "Готово", "Fertig", "完成", "完了"),
        t("Enregistrer", "Save", "Guardar", "Сохранить", "Speichern", "保存", "保存"),
        t("Supprimer", "Delete", "Eliminar", "Удалить", "Löschen", "删除", "削除"),
        t("Modifier", "Edit", "Editar", "Изменить", "Bearbeiten", "编辑", "編集"),
        t("Ajouter", "Add", "Añadir", "Добавить", "Hinzufügen", "添加", "追加"),
        t("Choisir", "Choose", "Elegir", "Выбрать", "Auswählen", "选择", "選択"),
        t("Tout", "All", "Todo", "Все", "Alle", "全部", "すべて"),
        t("Aucun", "None", "Ninguno", "Нет", "Keine", "无", "なし"),
        t("Aucune", "None", "Ninguna", "Нет", "Keine", "无", "なし"),
        t("Oui", "Yes", "Sí", "Да", "Ja", "是", "はい"),
        t("Non", "No", "No", "Нет", "Nein", "否", "いいえ"),
        t("Scanner", "Scan", "Escanear", "Сканировать", "Scannen", "扫描", "スキャン"),
        t("Scanner ma bibliothèque", "Scan my library", "Escanear mi biblioteca", "Сканировать библиотеку", "Meine Bibliothek scannen", "扫描我的资料库", "ライブラリをスキャン"),
        t("Ajouter une source", "Add a game folder", "Añadir una carpeta de juegos", "Добавить папку с играми", "Spieleordner hinzufügen", "添加游戏文件夹", "ゲームフォルダを追加"),
        t("Configurer plus tard", "Set up later", "Configurar más tarde", "Настроить позже", "Später einrichten", "稍后设置", "後で設定"),
        t("Toute votre bibliothèque. Un seul ciel.", "Your whole library. One sky.", "Toda tu biblioteca. Un solo cielo.", "Вся ваша библиотека. Одно небо.", "Deine ganze Bibliothek. Ein Himmel.", "整个游戏库，尽在一片天空下。", "すべてのライブラリを、ひとつの空に。"),
        t("Astra détecte, classe et lance vos jeux JoiPlay sans modifier leurs fichiers.", "Astra detects, organizes and launches your JoiPlay games without changing their files.", "Astra detecta, organiza y lanza tus juegos de JoiPlay sin modificar sus archivos.", "Astra находит, сортирует и запускает игры JoiPlay, не изменяя их файлы.", "Astra erkennt, organisiert und startet deine JoiPlay-Spiele, ohne ihre Dateien zu ändern.", "Astra 会检测、整理并启动 JoiPlay 游戏，不会修改游戏文件。", "Astra はファイルを変更せずに JoiPlay のゲームを検出・整理・起動します。"),
        t("Langue", "Language", "Idioma", "Язык", "Sprache", "语言", "言語"),
        t("Choisissez la langue d’Astra", "Choose Astra's language", "Elige el idioma de Astra", "Выберите язык Astra", "Astra-Sprache auswählen", "选择 Astra 的语言", "Astra の言語を選択"),
        t("Continuer", "Continue", "Continuar", "Продолжить", "Weiter", "继续", "続ける"),
        t("Choisissez votre dossier de jeux", "Choose your game folder", "Elige tu carpeta de juegos", "Выберите папку с играми", "Spieleordner auswählen", "选择游戏文件夹", "ゲームフォルダを選択"),
        t("Astra parcourra récursivement tous les niveaux du dossier.", "Astra will scan every level below this folder.", "Astra recorrerá todos los niveles de esta carpeta.", "Astra просканирует все вложенные папки.", "Astra durchsucht alle Unterordner dieses Ordners.", "Astra 会递归扫描此文件夹下的所有层级。", "Astra はこのフォルダ以下を再帰的にスキャンします。"),
        t("Importer des tags", "Import tags", "Importar etiquetas", "Импортировать теги", "Tags importieren", "导入标签", "タグをインポート"),
        t("Importez un fichier texte, CSV ou JSON, ou passez cette étape.", "Import a text, CSV or JSON file, or skip this step.", "Importa un archivo de texto, CSV o JSON, o sáltate este paso.", "Импортируйте TXT, CSV или JSON либо пропустите этот шаг.", "Importiere eine Text-, CSV- oder JSON-Datei oder überspringe diesen Schritt.", "导入文本、CSV 或 JSON 文件，也可以跳过此步骤。", "テキスト、CSV、JSON ファイルを読み込むか、この手順をスキップします。"),
        t("Runtimes JoiPlay", "JoiPlay runtimes", "Runtimes de JoiPlay", "Среды JoiPlay", "JoiPlay-Runtimes", "JoiPlay 运行时", "JoiPlay ランタイム"),
        t("Vérifier les runtimes", "Check runtimes", "Comprobar runtimes", "Проверить среды", "Runtimes prüfen", "检查运行时", "ランタイムを確認"),
        t("Requis par votre bibliothèque", "Required by your library", "Necesario para tu biblioteca", "Требуется вашей библиотеке", "Von deiner Bibliothek benötigt", "你的资料库需要", "ライブラリで必要"),
        t("Télécharger", "Download", "Descargar", "Скачать", "Herunterladen", "下载", "ダウンロード"),
        t("Mettre à jour", "Update", "Actualizar", "Обновить", "Aktualisieren", "更新", "更新"),
        t("Scanner au lancement", "Scan on launch", "Escanear al iniciar", "Сканировать при запуске", "Beim Start scannen", "启动时扫描", "起動時にスキャン"),
        t("Sources et scan", "Sources and scanning", "Fuentes y escaneo", "Источники и сканирование", "Quellen und Scan", "来源与扫描", "ソースとスキャン"),
        t("Apparence", "Appearance", "Apariencia", "Внешний вид", "Erscheinungsbild", "外观", "外観"),
        t("Flou des jaquettes", "Cover blur", "Desenfoque de carátulas", "Размытие обложек", "Cover-Unschärfe", "封面模糊", "カバーのぼかし"),
        t("Désactivé", "Off", "Desactivado", "Отключено", "Aus", "关闭", "オフ"),
        t("Automatique au démarrage", "Automatic on startup", "Automático al iniciar", "Автоматически при запуске", "Automatisch beim Start", "启动时自动", "起動時に自動"),
        t("Manuel", "Manual", "Manual", "Вручную", "Manuell", "手动", "手動"),
        t("Afficher les jaquettes", "Show covers", "Mostrar carátulas", "Показать обложки", "Cover anzeigen", "显示封面", "カバーを表示"),
        t("Flouter les jaquettes", "Blur covers", "Desenfocar carátulas", "Размыть обложки", "Cover ausblenden", "模糊封面", "カバーをぼかす"),
        t("Couleurs dynamiques", "Dynamic colors", "Colores dinámicos", "Динамические цвета", "Dynamische Farben", "动态颜色", "ダイナミックカラー"),
        t("Organisation", "Organization", "Organización", "Организация", "Organisation", "整理", "整理"),
        t("Sauvegarde et restauration", "Backup and restore", "Copia y restauración", "Резервное копирование и восстановление", "Sicherung und Wiederherstellung", "备份与恢复", "バックアップと復元"),
        t("Gestionnaire de runtimes", "Runtime manager", "Gestor de runtimes", "Менеджер сред", "Runtime-Manager", "运行时管理器", "ランタイム管理"),
        t("JoiPlay non détecté", "JoiPlay not detected", "JoiPlay no detectado", "JoiPlay не обнаружен", "JoiPlay nicht erkannt", "未检测到 JoiPlay", "JoiPlay が見つかりません"),
        t("Tags et catégories", "Tags and categories", "Etiquetas y categorías", "Теги и категории", "Tags und Kategorien", "标签与分类", "タグとカテゴリ"),
        t("Créer, importer, classer, modifier ou supprimer", "Create, import, organize, edit or delete", "Crear, importar, organizar, editar o eliminar", "Создавайте, импортируйте, сортируйте, изменяйте и удаляйте", "Erstellen, importieren, organisieren, bearbeiten oder löschen", "创建、导入、整理、编辑或删除", "作成、読み込み、整理、編集、削除"),
        t("Détection des doublons", "Duplicate detection", "Detección de duplicados", "Поиск дубликатов", "Duplikaterkennung", "重复项检测", "重複検出"),
        t("Choisir le dossier de sauvegarde", "Choose backup folder", "Elegir carpeta de copia", "Выбрать папку для резервных копий", "Sicherungsordner auswählen", "选择备份文件夹", "バックアップフォルダを選択"),
        t("Changer le dossier de sauvegarde", "Change backup folder", "Cambiar carpeta de copia", "Изменить папку для резервных копий", "Sicherungsordner ändern", "更改备份文件夹", "バックアップフォルダを変更"),
        t("Aucun dossier configuré", "No folder configured", "Ninguna carpeta configurada", "Папка не настроена", "Kein Ordner eingerichtet", "未配置文件夹", "フォルダ未設定"),
        t("Sauvegarder maintenant", "Back up now", "Crear copia ahora", "Создать резервную копию", "Jetzt sichern", "立即备份", "今すぐバックアップ"),
        t("Restaurer une sauvegarde", "Restore a backup", "Restaurar una copia", "Восстановить резервную копию", "Sicherung wiederherstellen", "恢复备份", "バックアップを復元"),
        t("Actualiser les métadonnées manquantes", "Refresh missing metadata", "Actualizar metadatos faltantes", "Обновить отсутствующие метаданные", "Fehlende Metadaten aktualisieren", "刷新缺失的元数据", "不足しているメタデータを更新"),
        t("Jaquettes, descriptions et développeurs • VNDB, sans tags", "Covers, descriptions and developers • VNDB, no tags", "Carátulas, descripciones y desarrolladores • VNDB, sin etiquetas", "Обложки, описания и разработчики • VNDB, без тегов", "Cover, Beschreibungen und Entwickler • VNDB, ohne Tags", "封面、简介和开发者 • VNDB，不导入标签", "カバー、説明、開発者 • VNDB、タグなし"),
        t("Moteur de recherche", "Search engine", "Motor de búsqueda", "Поисковая система", "Suchmaschine", "搜索引擎", "検索エンジン"),
        t("Choisir le moteur de recherche", "Choose a search engine", "Elegir un motor de búsqueda", "Выберите поисковую систему", "Suchmaschine auswählen", "选择搜索引擎", "検索エンジンを選択"),
        t("Les recherches du moteur sélectionné s’ouvrent directement dans le navigateur du téléphone.", "Searches from the selected engine open directly in the phone browser.", "Las búsquedas del motor seleccionado se abren directamente en el navegador del teléfono.", "Поиск выбранной системы открывается прямо в браузере телефона.", "Suchen der ausgewählten Suchmaschine werden direkt im Handy-Browser geöffnet.", "所选搜索引擎的搜索会直接在手机浏览器中打开。", "選択した検索エンジンの検索をスマートフォンのブラウザで直接開きます。"),
        t("Ouvrir dans le navigateur", "Open in browser", "Abrir en el navegador", "Открыть в браузере", "Im Browser öffnen", "在浏览器中打开", "ブラウザで開く"),
        t("Afficher dans Astra", "Show in Astra", "Mostrar en Astra", "Показать в Astra", "In Astra anzeigen", "在 Astra 中显示", "Astra で表示"),
        t("Rechercher automatiquement", "Search automatically", "Buscar automáticamente", "Искать автоматически", "Automatisch suchen", "自动搜索", "自動検索"),
        t("Aucune image trouvée automatiquement.", "No image found automatically.", "No se encontró ninguna imagen automáticamente.", "Автоматически изображения не найдены.", "Automatisch wurden keine Bilder gefunden.", "未自动找到图片。", "自動検索で画像が見つかりません。"),
        t("Recherche d’images", "Image search", "Búsqueda de imágenes", "Поиск изображений", "Bildersuche", "图片搜索", "画像検索"),
        t("Recherche d’images…", "Image search…", "Búsqueda de imágenes…", "Поиск изображений…", "Bildersuche…", "图片搜索…", "画像検索…"),
        t("Rechercher dans le navigateur", "Open searches in browser", "Abrir búsquedas en el navegador", "Открывать поиск в браузере", "Suchen im Browser öffnen", "在浏览器中打开搜索", "検索をブラウザで開く"),
        t("Favoris", "Favorites", "Favoritos", "Избранное", "Favoriten", "收藏", "お気に入り"),
        t("Filtres", "Filters", "Filtros", "Фильтры", "Filter", "筛选", "フィルター"),
        t("Réinitialiser", "Reset", "Restablecer", "Сбросить", "Zurücksetzen", "重置", "リセット"),
        t("Afficher les jeux", "Show games", "Mostrar juegos", "Показать игры", "Spiele anzeigen", "显示游戏", "ゲームを表示"),
        t("Favoris uniquement", "Favorites only", "Solo favoritos", "Только избранное", "Nur Favoriten", "仅收藏", "お気に入りのみ"),
        t("Jeux introuvables", "Missing games", "Juegos faltantes", "Пропавшие игры", "Fehlende Spiele", "缺失的游戏", "見つからないゲーム"),
        t("Tous les moteurs", "All engines", "Todos los motores", "Все движки", "Alle Engines", "所有引擎", "すべてのエンジン"),
        t("Tous les dossiers système", "All system folders", "Todas las carpetas del sistema", "Все системные папки", "Alle Systemordner", "所有系统文件夹", "すべてのシステムフォルダ"),
        t("Tous les tags", "All tags", "Todas las etiquetas", "Все теги", "Alle Tags", "所有标签", "すべてのタグ"),
        t("Titre, moteur, développeur…", "Title, engine, developer…", "Título, motor, desarrollador…", "Название, движок, разработчик…", "Titel, Engine, Entwickler…", "标题、引擎、开发者…", "タイトル、エンジン、開発者…"),
        t("Votre bibliothèque attend ses jeux", "Your library is waiting for games", "Tu biblioteca espera tus juegos", "Ваша библиотека ждёт игры", "Deine Bibliothek wartet auf Spiele", "你的资料库正在等待游戏", "ライブラリにゲームを追加しましょう"),
        t("Exploration de tous les sous-dossiers…", "Scanning all subfolders…", "Explorando todas las subcarpetas…", "Сканирование всех подпапок…", "Alle Unterordner werden durchsucht…", "正在扫描所有子文件夹…", "すべてのサブフォルダをスキャン中…"),
        t("Description", "Description", "Descripción", "Описание", "Beschreibung", "简介", "説明"),
        t("Informations", "Information", "Información", "Информация", "Informationen", "信息", "情報"),
        t("Moteur", "Engine", "Motor", "Движок", "Engine", "引擎", "エンジン"),
        t("Lancements", "Launches", "Lanzamientos", "Запусков", "Starts", "启动次数", "起動回数"),
        t("Temps de jeu", "Play time", "Tiempo de juego", "Время игры", "Spielzeit", "游戏时间", "プレイ時間"),
        t("Source", "Source", "Fuente", "Источник", "Quelle", "来源", "ソース"),
        t("Jouer", "Play", "Jugar", "Играть", "Spielen", "开始游戏", "プレイ"),
        t("Compatibilité", "Compatibility", "Compatibilidad", "Совместимость", "Kompatibilität", "兼容性", "互換性"),
        t("Vérification en cours…", "Checking…", "Comprobando…", "Проверка…", "Prüfung läuft…", "正在检查…", "確認中…"),
        t("Aucun tag associé", "No tags assigned", "Ninguna etiqueta asignada", "Теги не назначены", "Keine Tags zugewiesen", "未关联标签", "タグはありません"),
        t("Classer dans un dossier", "Organize in a folder", "Organizar en una carpeta", "Переместить в папку", "In einem Ordner ablegen", "整理到文件夹", "フォルダに整理"),
        t("Ouvrir le dossier des sauvegardes", "Open save folder", "Abrir carpeta de guardados", "Открыть папку сохранений", "Save-Ordner öffnen", "打开存档文件夹", "セーブフォルダを開く"),
        t("Ce jeu est introuvable. Rescannez sa source.", "This game cannot be found. Scan its source again.", "No se encuentra este juego. Vuelve a escanear su fuente.", "Игра не найдена. Повторно просканируйте источник.", "Dieses Spiel wurde nicht gefunden. Scanne die Quelle erneut.", "找不到此游戏，请重新扫描来源。", "このゲームが見つかりません。ソースを再スキャンしてください。"),
        t("Jaquette", "Cover", "Carátula", "Обложка", "Cover", "封面", "カバー"),
        t("Changer", "Change", "Cambiar", "Изменить", "Ändern", "更换", "変更"),
        t("Modifier le jeu", "Edit game", "Editar juego", "Изменить игру", "Spiel bearbeiten", "编辑游戏", "ゲームを編集"),
        t("Métadonnées", "Metadata", "Metadatos", "Метаданные", "Metadaten", "元数据", "メタデータ"),
        t("Zone sensible", "Danger zone", "Zona peligrosa", "Опасная зона", "Gefahrenzone", "危险区域", "危険ゾーン"),
        t("Importer depuis F95Zone", "Import from F95Zone", "Importar desde F95Zone", "Импортировать из F95Zone", "Aus F95Zone importieren", "从 F95Zone 导入", "F95Zone から読み込む"),
        t("Titre", "Title", "Título", "Название", "Titel", "标题", "タイトル"),
        t("Titre original", "Original title", "Título original", "Оригинальное название", "Originaltitel", "原始标题", "原題"),
        t("Développeur", "Developer", "Desarrollador", "Разработчик", "Entwickler", "开发者", "開発者"),
        t("Lien F95Zone", "F95Zone link", "Enlace F95Zone", "Ссылка F95Zone", "F95Zone-Link", "F95Zone 链接", "F95Zone リンク"),
        t("Ajouter des tags", "Add tags", "Añadir etiquetas", "Добавить теги", "Tags hinzufügen", "添加标签", "タグを追加"),
        t("Séparez-les par des virgules ou écrivez [tag] [tag]. Les tags existants seront réutilisés.", "Separate them with commas or write [tag] [tag]. Existing tags will be reused.", "Sepáralas con comas o escribe [tag] [tag]. Se reutilizarán las etiquetas existentes.", "Разделяйте запятыми или пишите [tag] [tag]. Существующие теги будут переиспользованы.", "Trenne sie mit Kommas oder schreibe [tag] [tag]. Vorhandene Tags werden wiederverwendet.", "用逗号分隔，或写成 [tag] [tag]。现有标签会被复用。", "カンマで区切るか [tag] [tag] と入力してください。既存のタグを再利用します。"),
        t("Choisir les tags", "Choose tags", "Elegir etiquetas", "Выбрать теги", "Tags auswählen", "选择标签", "タグを選択"),
        t("Sélectionner les tags", "Select tags", "Seleccionar etiquetas", "Выбрать теги", "Tags auswählen", "标签を选择", "タグを選択"),
        t("Choisir une image", "Choose an image", "Elegir una imagen", "Выбрать изображение", "Bild auswählen", "选择图片", "画像を選択"),
        t("L’image choisie pourra être recadrée avant enregistrement.", "The selected image can be cropped before saving.", "La imagen elegida se puede recortar antes de guardarla.", "Выбранное изображение можно обрезать перед сохранением.", "Das ausgewählte Bild kann vor dem Speichern zugeschnitten werden.", "选中的图片可以在保存前裁剪。", "選択した画像は保存前にトリミングできます。"),
        t("Importer", "Import", "Importar", "Импортировать", "Importieren", "导入", "読み込む"),
        t("Sans image", "Without image", "Sin imagen", "Без изображения", "Ohne Bild", "不使用图片", "画像なし"),
        t("Analyser le lien", "Analyze link", "Analizar enlace", "Проанализировать ссылку", "Link analysieren", "分析链接", "リンクを解析"),
        t("Lien du thread", "Thread link", "Enlace del hilo", "Ссылка на тему", "Thread-Link", "主题链接", "スレッドリンク"),
        t("Rechercher le thread sur Yandex", "Search for the thread on Yandex", "Buscar el hilo en Yandex", "Найти тему в Yandex", "Thread auf Yandex suchen", "在 Yandex 中搜索主题", "Yandex でスレッドを検索"),
        t("Rechercher sur Yandex", "Search on Yandex", "Buscar en Yandex", "Поиск в Yandex", "Auf Yandex suchen", "在 Yandex 中搜索", "Yandex で検索"),
        t("Appui long sur le bon résultat F95Zone", "Long-press the correct F95Zone result", "Mantén pulsado el resultado correcto de F95Zone", "Удерживайте правильный результат F95Zone", "Halte das richtige F95Zone-Ergebnis gedrückt", "长按正确的 F95Zone 结果", "正しい F95Zone の結果を長押し"),
        t("Étape", "Step", "Paso", "Шаг", "Schritt", "步骤", "ステップ"),
        t("Tag existant", "Existing tag", "Etiqueta existente", "Существующий тег", "Vorhandener Tag", "已有标签", "既存のタグ"),
        t("Nouveau tag · F95Zone", "New tag · F95Zone", "Nueva etiqueta · F95Zone", "Новый тег · F95Zone", "Neuer Tag · F95Zone", "新标签 · F95Zone", "新しいタグ · F95Zone"),
        t("Aucun tag détecté sur ce thread.", "No tags found on this thread.", "No se encontraron etiquetas en este hilo.", "В этой теме теги не найдены.", "Keine Tags in diesem Thread gefunden.", "此主题中未找到标签。", "このスレッドにタグはありません。"),
        t("Aucune image détectée sur ce thread.", "No image found on this thread.", "No se encontró ninguna imagen en este hilo.", "В этой теме изображения не найдены.", "Kein Bild in diesem Thread gefunden.", "此主题中未找到图片。", "このスレッドに画像はありません。"),
        t("Image locale", "Local image", "Imagen local", "Локальное изображение", "Lokales Bild", "本地图片", "ローカル画像"),
        t("Image sélectionnée", "Selected image", "Imagen seleccionada", "Выбранное изображение", "Ausgewähltes Bild", "已选图片", "選択した画像"),
        t("Utiliser l’image affichée", "Use displayed image", "Usar la imagen mostrada", "Использовать показанное изображение", "Angezeigtes Bild verwenden", "使用当前图片", "表示中の画像を使う"),
        t("Ouvrir directement l’image", "Open image directly", "Abrir imagen directamente", "Открыть изображение", "Bild direkt öffnen", "直接打开图片", "画像を直接開く"),
        t("Utiliser cette image", "Use this image", "Usar esta imagen", "Использовать это изображение", "Dieses Bild verwenden", "使用此图片", "この画像を使う"),
        t("Choisissez l’action à effectuer avec cette image.", "Choose what to do with this image.", "Elige qué hacer con esta imagen.", "Выберите действие для этого изображения.", "Wähle eine Aktion für dieses Bild.", "选择对这张图片的操作。", "この画像に対する操作を選択してください。"),
        t("Recherche des sauvegardes…", "Searching for saves…", "Buscando partidas guardadas…", "Поиск сохранений…", "Speicherstände werden gesucht…", "正在查找存档…", "セーブを検索中…"),
        t("Après la fusion", "After merging", "Después de fusionar", "После объединения", "Nach dem Zusammenführen", "合并后", "統合後"),
        t("Supprimer le dossier secondaire", "Delete the secondary folder", "Eliminar la carpeta secundaria", "Удалить дополнительную папку", "Sekundären Ordner löschen", "删除次要文件夹", "重複側のフォルダを削除"),
        t("Ignorer ce groupe", "Ignore this group", "Ignorar este grupo", "Игнорировать эту группу", "Diese Gruppe ignorieren", "忽略此组", "このグループを無視"),
        t("Fusionner", "Merge", "Fusionar", "Объединить", "Zusammenführen", "合并", "統合"),
        t("Sauvegardes", "Saves", "Guardados", "Сохранения", "Speicherstände", "存档", "セーブ"),
        t("Récupérer les sauvegardes du doublon", "Recover saves from the duplicate", "Recuperar los guardados del duplicado", "Перенести сохранения из дубликата", "Speicherstände des Duplikats übernehmen", "恢复重复项的存档", "重複側のセーブを復元"),
        t("Jeux supprimés", "Deleted games", "Juegos eliminados", "Удалённые игры", "Gelöschte Spiele", "已删除的游戏", "削除したゲーム"),
        t("Réautoriser", "Restore", "Volver a autorizar", "Разрешить снова", "Wieder zulassen", "恢复允许", "再許可"),
        t("Synchronisation en cours", "Sync in progress", "Sincronización en curso", "Синхронизация выполняется", "Synchronisierung läuft", "正在同步", "同期中"),
        t("Préparation…", "Preparing…", "Preparando…", "Подготовка…", "Vorbereitung…", "准备中…", "準備中…"),
        t("Vérification de la configuration…", "Checking configuration…", "Comprobando la configuración…", "Проверка конфигурации…", "Konfiguration wird geprüft…", "正在检查配置…", "設定を確認中…"),
        t("Diagnostic", "Diagnostics", "Diagnóstico", "Диагностика", "Diagnose", "诊断", "診断"),
        t("Profil de lancement", "Launch profile", "Perfil de lanzamiento", "Профиль запуска", "Startprofil", "启动配置", "起動プロファイル"),
        t("Application externe", "External app", "Aplicación externa", "Внешнее приложение", "Externe App", "外部应用", "外部アプリ"),
        t("Automatique", "Automatic", "Automático", "Автоматически", "Automatisch", "自动", "自動"),
        t("Tester", "Test", "Probar", "Проверить", "Testen", "测试", "テスト"),
        t("Rétablir la détection automatique", "Restore automatic detection", "Restaurar detección automática", "Восстановить автоматическое определение", "Automatische Erkennung wiederherstellen", "恢复自动检测", "自動検出に戻す"),
        t("Terminer", "Finish", "Terminar", "Завершить", "Fertigstellen", "完成", "完了"),
        t("Enregistrer et suivant", "Save and next", "Guardar y siguiente", "Сохранить и далее", "Speichern und weiter", "保存并继续", "保存して次へ"),
        t("Ignorer ce jeu", "Skip this game", "Omitir este juego", "Пропустить игру", "Dieses Spiel überspringen", "跳过此游戏", "このゲームをスキップ"),
        t("Voulez-vous configurer chaque jeu maintenant ? Astra passera automatiquement au suivant.", "Would you like to configure each game now? Astra will move to the next one automatically.", "¿Quieres configurar cada juego ahora? Astra pasará al siguiente automáticamente.", "Настроить каждую игру сейчас? Astra автоматически перейдёт к следующей.", "Möchtest du jedes Spiel jetzt konfigurieren? Astra wechselt automatisch zum nächsten.", "现在配置每个游戏吗？Astra 会自动进入下一个。", "今すぐ各ゲームを設定しますか？Astra が自動的に次へ進みます。"),
        t("Plus tard", "Later", "Más tarde", "Позже", "Später", "稍后", "後で"),
        t("Modifier la collection", "Edit collection", "Editar colección", "Изменить коллекцию", "Sammlung bearbeiten", "编辑收藏", "コレクションを編集"),
        t("Collection intelligente personnelle", "Personal smart collection", "Colección inteligente personal", "Личная умная коллекция", "Persönliche intelligente Sammlung", "个人智能收藏", "個人スマートコレクション"),
        t("Collections intelligentes", "Smart collections", "Colecciones inteligentes", "Умные коллекции", "Intelligente Sammlungen", "智能收藏", "スマートコレクション"),
        t("Nouvelle catégorie", "New category", "Nueva categoría", "Новая категория", "Neue Kategorie", "新建分类", "新しいカテゴリ"),
        t("Renommer la catégorie", "Rename category", "Renombrar categoría", "Переименовать категорию", "Kategorie umbenennen", "重命名分类", "カテゴリ名を変更"),
        t("Nouveau tag", "New tag", "Nueva etiqueta", "Новый тег", "Neuer Tag", "新标签", "新しいタグ"),
        t("Modifier le tag", "Edit tag", "Editar etiqueta", "Изменить тег", "Tag bearbeiten", "编辑标签", "タグを編集"),
        t("Catégorie", "Category", "Categoría", "Категория", "Kategorie", "分类", "カテゴリ"),
        t("Sans catégorie", "No category", "Sin categoría", "Без категории", "Keine Kategorie", "无分类", "カテゴリなし"),
        t("Nom", "Name", "Nombre", "Название", "Name", "名称", "名前"),
        t("Nouvelle collection", "New collection", "Nueva colección", "Новая коллекция", "Neue Sammlung", "新建收藏", "新しいコレクション"),
        t("Correspondance", "Match", "Coincidencia", "Соответствие", "Übereinstimmung", "匹配", "一致条件"),
        t("Toutes les règles (ET)", "All rules (AND)", "Todas las reglas (Y)", "Все правила (И)", "Alle Regeln (UND)", "所有规则（与）", "すべての条件（AND）"),
        t("Au moins une (OU)", "At least one (OR)", "Al menos una (O)", "Хотя бы одно (ИЛИ)", "Mindestens eine (ODER)", "至少一条（或）", "1つ以上（OR）"),
        t("Ajouter une règle", "Add a rule", "Añadir una regla", "Добавить правило", "Regel hinzufügen", "添加规则", "ルールを追加"),
        t("Règle de collection", "Collection rule", "Regla de colección", "Правило коллекции", "Sammlungsregel", "收藏规则", "コレクションルール"),
        t("Champ", "Field", "Campo", "Поле", "Feld", "字段", "フィールド"),
        t("Condition", "Condition", "Condición", "Условие", "Bedingung", "条件", "条件"),
        t("Valeur", "Value", "Valor", "Значение", "Wert", "值", "値"),
        t("Rechercher un tag", "Search for a tag", "Buscar una etiqueta", "Найти тег", "Tag suchen", "搜索标签", "タグを検索"),
        t("Aucun tag trouvé", "No tags found", "No se encontraron etiquetas", "Теги не найдены", "Keine Tags gefunden", "未找到标签", "タグが見つかりません"),
        t("Aucun doublon détecté", "No duplicates detected", "No se detectaron duplicados", "Дубликаты не найдены", "Keine Duplikate erkannt", "未检测到重复项", "重複はありません"),
        t("Aucun jeu supprimé", "No deleted games", "No hay juegos eliminados", "Удалённых игр нет", "Keine gelöschten Spiele", "没有已删除的游戏", "削除したゲームはありません"),
        t("Aucun dossier ni jeu ici", "No folder or game here", "No hay carpetas ni juegos aquí", "Здесь нет папок или игр", "Hier gibt es keine Ordner oder Spiele", "此处没有文件夹或游戏", "ここにフォルダやゲームはありません"),
        t("Aucun détail disponible pour ce rapport.", "No details available for this report.", "No hay detalles disponibles para este informe.", "Для этого отчёта нет подробностей.", "Für diesen Bericht sind keine Details verfügbar.", "此报告没有可用详情。", "このレポートに詳細はありません。"),
        t("Aucun tag dans cette catégorie", "No tags in this category", "No hay etiquetas en esta categoría", "В этой категории нет тегов", "Keine Tags in dieser Kategorie", "此分类没有标签", "このカテゴリにタグはありません"),
        t("Supprimer la jaquette ?", "Delete cover?", "¿Eliminar la carátula?", "Удалить обложку?", "Cover löschen?", "删除封面？", "カバーを削除しますか？"),
        t("Le jeu restera dans la bibliothèque. Seule sa jaquette sera retirée.", "The game will stay in the library. Only its cover will be removed.", "El juego permanecerá en la biblioteca. Solo se quitará la carátula.", "Игра останется в библиотеке. Будет удалена только обложка.", "Das Spiel bleibt in der Bibliothek. Nur das Cover wird entfernt.", "游戏会保留在资料库中，只删除封面。", "ゲームはライブラリに残り、カバーだけを削除します。"),
        t("Supprimer le jeu", "Delete game", "Eliminar juego", "Удалить игру", "Spiel löschen", "删除游戏", "ゲームを削除"),
        t("Supprimer aussi les fichiers associés", "Also delete associated files", "Eliminar también los archivos asociados", "Также удалить связанные файлы", "Zugehörige Dateien ebenfalls löschen", "同时删除相关文件", "関連ファイルも削除"),
        t("Cette action est irréversible et le jeu ne figurera pas dans l’historique des jeux supprimés.", "This action is irreversible and the game will not appear in deleted-game history.", "Esta acción es irreversible y el juego no aparecerá en el historial de juegos eliminados.", "Это действие необратимо, и игра не появится в истории удалённых игр.", "Diese Aktion ist irreversibel und das Spiel erscheint nicht im Verlauf gelöschter Spiele.", "此操作不可撤销，游戏不会出现在已删除游戏历史中。", "この操作は取り消せず、削除したゲームの履歴にも残りません。"),
        t("Démarrer une sélection multiple", "Start multi-selection", "Iniciar selección múltiple", "Начать множественный выбор", "Mehrfachauswahl starten", "开始多选", "複数選択を開始"),
        t("Ouvrir la fiche", "Open details", "Abrir ficha", "Открыть карточку", "Details öffnen", "打开详情", "詳細を開く"),
        t("Retirer des favoris", "Remove from favorites", "Quitar de favoritos", "Убрать из избранного", "Aus Favoriten entfernen", "取消收藏", "お気に入りから削除"),
        t("Ajouter aux favoris", "Add to favorites", "Añadir a favoritos", "Добавить в избранное", "Zu Favoriten hinzufügen", "加入收藏", "お気に入りに追加"),
        t("Rapport", "Report", "Informe", "Отчёт", "Bericht", "报告", "レポート"),
        t("Terminé", "Finished", "Terminado", "Завершено", "Fertig", "已完成", "完了"),
        t("Trouvés", "Found", "Encontrados", "Найдено", "Gefunden", "找到", "検出"),
        t("Ajoutés", "Added", "Añadidos", "Добавлено", "Hinzugefügt", "已添加", "追加"),
        t("Actualisés", "Updated", "Actualizados", "Обновлено", "Aktualisiert", "已更新", "更新"),
        t("Déjà connus", "Already known", "Ya conocidos", "Уже известные", "Bereits bekannt", "已知", "既知"),
        t("Déplacés", "Moved", "Movidos", "Перемещено", "Verschoben", "已移动", "移動"),
        t("Manquants", "Missing", "Faltantes", "Отсутствует", "Fehlend", "缺失", "不足"),
        t("Ignorés", "Ignored", "Ignorados", "Проигнорировано", "Ignoriert", "已忽略", "無視"),
        t("Erreurs", "Errors", "Errores", "Ошибки", "Fehler", "错误", "エラー"),
        t("Aucun rapport disponible pour cette source", "No report available for this source", "No hay informe para esta fuente", "Для этого источника нет отчёта", "Kein Bericht für diese Quelle verfügbar", "此来源没有报告", "このソースのレポートはありません"),
        t("Source ajoutée", "Source added", "Fuente añadida", "Источник добавлен", "Quelle hinzugefügt", "来源已添加", "ソースを追加しました"),
        t("Source retirée. Les fichiers du dossier sont conservés.", "Source removed. Folder files are kept.", "Fuente eliminada. Los archivos de la carpeta se conservan.", "Источник удалён. Файлы папки сохранены.", "Quelle entfernt. Die Dateien des Ordners bleiben erhalten.", "来源已移除，文件夹中的文件会保留。", "ソースを削除しました。フォルダのファイルは保持されます。"),
        t("Tags enregistrés", "Tags saved", "Etiquetas guardadas", "Теги сохранены", "Tags gespeichert", "标签已保存", "タグを保存しました"),
        t("Tag créé", "Tag created", "Etiqueta creada", "Тег создан", "Tag erstellt", "标签已创建", "タグを作成しました"),
        t("Tag modifié", "Tag updated", "Etiqueta modificada", "Тег изменён", "Tag geändert", "标签已修改", "タグを変更しました"),
        t("Catégorie appliquée", "Category applied", "Categoría aplicada", "Категория применена", "Kategorie angewendet", "分类已应用", "カテゴリを適用しました"),
        t("Collection intelligente enregistrée", "Smart collection saved", "Colección inteligente guardada", "Умная коллекция сохранена", "Intelligente Sammlung gespeichert", "智能收藏已保存", "スマートコレクションを保存しました"),
        t("Collection supprimée", "Collection deleted", "Colección eliminada", "Коллекция удалена", "Sammlung gelöscht", "收藏已删除", "コレクションを削除しました"),
        t("Jaquette enregistrée", "Cover saved", "Carátula guardada", "Обложка сохранена", "Cover gespeichert", "封面已保存", "カバーを保存しました"),
        t("Jaquette supprimée", "Cover deleted", "Carátula eliminada", "Обложка удалена", "Cover gelöscht", "封面已删除", "カバーを削除しました"),
        t("Jeu mis à jour", "Game updated", "Juego actualizado", "Игра обновлена", "Spiel aktualisiert", "游戏已更新", "ゲームを更新しました"),
        t("Jeu configuré", "Game configured", "Juego configurado", "Игра настроена", "Spiel konfiguriert", "游戏已配置", "ゲームを設定しました"),
        t("Jeu réautorisé. Relancez le scan de sa source.", "Game restored. Scan its source again.", "Juego restaurado. Vuelve a escanear su fuente.", "Игра восстановлена. Повторно просканируйте источник.", "Spiel wieder zugelassen. Scanne die Quelle erneut.", "游戏已恢复，请重新扫描来源。", "ゲームを再許可しました。ソースを再スキャンしてください。"),
        t("Sauvegarde restaurée", "Backup restored", "Copia restaurada", "Резервная копия восстановлена", "Sicherung wiederhergestellt", "备份已恢复", "バックアップを復元しました"),
        t("Test envoyé au lanceur configuré", "Test sent to configured launcher", "Prueba enviada al lanzador configurado", "Тест отправлен настроенному лаунчеру", "Test an den konfigurierten Starter gesendet", "测试已发送到配置的启动器", "設定したランチャーにテストを送信しました"),
        t("Ce groupe de doublons sera désormais ignoré", "This duplicate group will now be ignored", "Este grupo de duplicados se ignorará", "Эта группа дубликатов будет игнорироваться", "Diese Duplikatgruppe wird jetzt ignoriert", "此重复组将被忽略", "この重複グループを無視します"),
        t("Détection automatique restaurée", "Automatic detection restored", "Detección automática restaurada", "Автоматическое определение восстановлено", "Automatische Erkennung wiederhergestellt", "自动检测已恢复", "自動検出に戻しました"),
        t("Profil de lancement enregistré", "Launch profile saved", "Perfil de lanzamiento guardado", "Профиль запуска сохранён", "Startprofil gespeichert", "启动配置已保存", "起動プロファイルを保存しました"),
        t("Astra vérifie les composants JoiPlay présents sur le téléphone.", "Astra checks the JoiPlay components installed on the phone.", "Astra comprueba los componentes de JoiPlay instalados en el teléfono.", "Astra проверяет компоненты JoiPlay на телефоне.", "Astra prüft die auf dem Telefon installierten JoiPlay-Komponenten.", "Astra 会检查手机上安装的 JoiPlay 组件。", "Astra がスマートフォンの JoiPlay コンポーネントを確認します。"),
        t("Aucun dossier sélectionné", "No folder selected", "No se ha seleccionado ninguna carpeta", "Папка не выбрана", "Kein Ordner ausgewählt", "未选择文件夹", "フォルダが選択されていません"),
        t("Aucun tag importé", "No tags imported", "No se han importado etiquetas", "Теги не импортированы", "Keine Tags importiert", "未导入标签", "タグは読み込まれていません"),
        t("Dossier sélectionné", "Selected folder", "Carpeta seleccionada", "Выбранная папка", "Ausgewählter Ordner", "已选文件夹", "選択したフォルダ"),
        t("La langue est modifiable à tout moment dans Paramètres.", "You can change the language at any time in Settings.", "Puedes cambiar el idioma en cualquier momento desde Ajustes.", "Язык можно изменить в настройках в любое время.", "Die Sprache kann jederzeit in den Einstellungen geändert werden.", "你可以随时在设置中更改语言。", "言語は設定からいつでも変更できます。"),
        t("Aucun runtime détecté", "No runtime detected", "No se detectó ningún runtime", "Среды не обнаружены", "Keine Runtime erkannt", "未检测到运行时", "ランタイムが見つかりません"),
        t("Non installé", "Not installed", "No instalado", "Не установлено", "Nicht installiert", "未安装", "未インストール"),
        t("Catégories", "Categories", "Categorías", "Категории", "Kategorien", "分类", "カテゴリ"),
        t("Catalogue, réglages de jeux et jaquettes", "Catalog, game settings and covers", "Catálogo, ajustes de juegos y carátulas", "Каталог, настройки игр и обложки", "Katalog, Spieleinstellungen und Cover", "目录、游戏设置和封面", "カタログ、ゲーム設定、カバー"),
        t("Dossier configuré et accessible par le bouton rapide ci-dessous", "Folder configured and available from the quick button below", "Carpeta configurada y accesible desde el botón rápido de abajo", "Папка настроена и доступна через кнопку ниже", "Ordner eingerichtet und über die Schnellschaltfläche erreichbar", "文件夹已配置，可通过下方快捷按钮访问", "フォルダを設定しました。下のショートカットから開けます"),
        t("Restaurer une sauvegarde ?", "Restore a backup?", "¿Restaurar una copia?", "Восстановить резервную копию?", "Sicherung wiederherstellen?", "恢复备份？", "バックアップを復元しますか？"),
        t("Le catalogue actuel sera remplacé par la sauvegarde sélectionnée. Les fichiers des jeux ne seront pas modifiés.", "The current catalog will be replaced by the selected backup. Game files will not be changed.", "El catálogo actual se sustituirá por la copia seleccionada. Los archivos de los juegos no cambiarán.", "Текущий каталог будет заменён выбранной копией. Файлы игр не изменятся.", "Der aktuelle Katalog wird durch die ausgewählte Sicherung ersetzt. Spieldateien werden nicht geändert.", "当前目录会被选中的备份替换，游戏文件不会改变。", "現在のカタログを選択したバックアップで置き換えます。ゲームファイルは変更されません。"),
        t("Dossier système", "System folder", "Carpeta del sistema", "Системная папка", "Systemordner", "系统文件夹", "システムフォルダ"),
        t("Tri", "Sort", "Ordenar", "Сортировка", "Sortieren", "排序", "並べ替え"),
        t("Ajouts récents", "Recently added", "Añadidos recientemente", "Недавно добавленные", "Kürzlich hinzugefügt", "最近添加", "最近追加"),
        t("Dernier lancement", "Last launch", "Último lanzamiento", "Последний запуск", "Letzter Start", "上次启动", "最終起動"),
        t("Plus joués", "Most played", "Más jugados", "Чаще всего играли", "Am häufigsten gespielt", "最常玩", "よく遊ぶ"),
        t("Tous", "All", "Todos", "Все", "Alle", "全部", "すべて"),
        t("Toutes", "All", "Todas", "Все", "Alle", "全部", "すべて"),
        t("Classer", "Organize", "Organizar", "Распределить", "Organisieren", "整理", "整理"),
        t("Classer le jeu", "Organize game", "Organizar juego", "Распределить игру", "Spiel organisieren", "整理游戏", "ゲームを整理"),
        t("Classer les tags", "Organize tags", "Organizar etiquetas", "Распределить теги", "Tags organisieren", "整理标签", "タグを整理"),
        t("Sans collection", "No collection", "Sin colección", "Без коллекции", "Keine Sammlung", "无收藏", "コレクションなし"),
        t("Sans dossier", "No folder", "Sin carpeta", "Без папки", "Kein Ordner", "无文件夹", "フォルダなし"),
        t("Jeux dans ce dossier", "Games in this folder", "Juegos en esta carpeta", "Игры в этой папке", "Spiele in diesem Ordner", "此文件夹中的游戏", "このフォルダのゲーム"),
        t("Mes collections intelligentes", "My smart collections", "Mis colecciones inteligentes", "Мои умные коллекции", "Meine intelligenten Sammlungen", "我的智能收藏", "マイスマートコレクション"),
        t("Les jeux ne seront pas supprimés. Ses sous-dossiers remonteront au niveau actuel.", "Games will not be deleted. Its subfolders will move up one level.", "Los juegos no se eliminarán. Sus subcarpetas subirán al nivel actual.", "Игры не будут удалены. Подпапки поднимутся на текущий уровень.", "Spiele werden nicht gelöscht. Unterordner werden eine Ebene nach oben verschoben.", "游戏不会删除，子文件夹会移动到当前层级。", "ゲームは削除されず、サブフォルダが現在の階層に移動します。"),
        t("Les jeux de cette source seront retirés de la bibliothèque Astra. Aucun fichier ne sera supprimé du téléphone.", "Games from this source will be removed from the Astra library. No phone files will be deleted.", "Los juegos de esta fuente se quitarán de la biblioteca Astra. No se eliminarán archivos del teléfono.", "Игры из этого источника будут удалены из библиотеки Astra. Файлы на телефоне не будут удалены.", "Spiele dieser Quelle werden aus der Astra-Bibliothek entfernt. Keine Dateien auf dem Telefon werden gelöscht.", "此来源的游戏会从 Astra 资料库中移除，不会删除手机上的文件。", "このソースのゲームを Astra ライブラリから削除します。スマートフォンのファイルは削除されません。"),
        t("Retirer la source", "Remove source", "Eliminar fuente", "Удалить источник", "Quelle entfernen", "移除来源", "ソースを削除"),
        t("Chemin du dossier", "Folder path", "Ruta de la carpeta", "Путь к папке", "Ordnerpfad", "文件夹路径", "フォルダのパス"),
        t("Exécutable ou fichier d'entrée", "Executable or entry file", "Ejecutable o archivo de entrada", "Исполняемый или входной файл", "Ausführbare oder Startdatei", "可执行文件或入口文件", "実行ファイルまたはエントリーファイル"),
        t("Package Android (facultatif)", "Android package (optional)", "Paquete Android (opcional)", "Пакет Android (необязательно)", "Android-Paket (optional)", "Android 包（可选）", "Android パッケージ（任意）"),
        t("Action Android", "Android action", "Acción de Android", "Действие Android", "Android-Aktion", "Android 操作", "Android アクション"),
        t("Arguments personnalisés", "Custom arguments", "Argumentos personalizados", "Пользовательские аргументы", "Benutzerdefinierte Argumente", "自定义参数", "カスタム引数"),
        t("Nombre de jours", "Number of days", "Número de días", "Количество дней", "Anzahl der Tage", "天数", "日数"),
        t("Durée en heures", "Duration in hours", "Duración en horas", "Продолжительность в часах", "Dauer in Stunden", "小时数", "時間（小时）"),
        t("Ils seront ignorés lors des prochains scans. Aucun fichier ne sera supprimé.", "They will be ignored during future scans. No files will be deleted.", "Se ignorarán en los próximos escaneos. No se eliminarán archivos.", "Они будут игнорироваться при следующих сканированиях. Файлы не будут удалены.", "Sie werden bei künftigen Scans ignoriert. Keine Dateien werden gelöscht.", "下次扫描时会忽略它们，不会删除任何文件。", "次回以降のスキャンで無視します。ファイルは削除されません。"),
        t("Aucun dossier de sauvegarde détecté", "No save folder detected", "No se detectó ninguna carpeta de guardado", "Папка сохранений не найдена", "Kein Save-Ordner erkannt", "未检测到存档文件夹", "セーブフォルダが見つかりません"),
        t("Choisissez d’abord un dossier de sauvegarde", "Choose a backup folder first", "Elige primero una carpeta de copia", "Сначала выберите папку для резервных копий", "Wähle zuerst einen Sicherungsordner", "请先选择备份文件夹", "先にバックアップフォルダを選択してください"),
        t("Recherche Yandex", "Yandex search", "Búsqueda de Yandex", "Поиск Yandex", "Yandex-Suche", "Yandex 搜索", "Yandex 検索"),
        t("Ouvrir la recherche sur Yandex", "Open search on Yandex", "Abrir la búsqueda en Yandex", "Открыть поиск в Yandex", "Suche auf Yandex öffnen", "在 Yandex 中打开搜索", "Yandex で検索を開く"),
        t("Yandex Images", "Yandex Images", "Yandex Imágenes", "Yandex Картинки", "Yandex-Bilder", "Yandex 图片", "Yandex 画像"),
        t("Ouvrir Yandex Images", "Open Yandex Images", "Abrir Yandex Imágenes", "Открыть Yandex Картинки", "Yandex-Bilder öffnen", "打开 Yandex 图片", "Yandex 画像を開く"),
        t("Touchez une image pour afficher son aperçu", "Tap an image to show its preview", "Toca una imagen para ver su vista previa", "Нажмите на изображение для предпросмотра", "Tippe auf ein Bild, um die Vorschau anzuzeigen", "点击图片查看预览", "画像をタップしてプレビューを表示"),
        t("Ce lien n’est pas un thread F95Zone valide. Maintenez le titre d’un résultat F95Zone.", "This link is not a valid F95Zone thread. Long-press the title of an F95Zone result.", "Este enlace no es un hilo F95Zone válido. Mantén pulsado el título de un resultado F95Zone.", "Это недействительная тема F95Zone. Удерживайте заголовок результата F95Zone.", "Dieser Link ist kein gültiger F95Zone-Thread. Halte den Titel eines F95Zone-Ergebnisses gedrückt.", "此链接不是有效的 F95Zone 主题，请长按 F95Zone 结果标题。", "このリンクは有効な F95Zone スレッドではありません。F95Zone の結果タイトルを長押ししてください。"),
        t("Cette image ne possède pas d’adresse HTTPS exploitable.", "This image has no usable HTTPS address.", "Esta imagen no tiene una dirección HTTPS utilizable.", "У изображения нет рабочего HTTPS-адреса.", "Dieses Bild hat keine nutzbare HTTPS-Adresse.", "此图片没有可用的 HTTPS 地址。", "この画像には利用できる HTTPS アドレスがありません。"),
        t("Touchez d’abord une image pour afficher son aperçu.", "Tap an image first to show its preview.", "Toca primero una imagen para ver su vista previa.", "Сначала нажмите на изображение для предпросмотра.", "Tippe zuerst auf ein Bild, um die Vorschau anzuzeigen.", "请先点击图片查看预览。", "まず画像をタップしてプレビューを表示してください。"),
        t("Aucun jeu ne correspond aux filtres", "No games match the filters", "Ningún juego coincide con los filtros", "Игры не соответствуют фильтрам", "Keine Spiele passen zu den Filtern", "没有符合筛选条件的游戏", "条件に一致するゲームはありません"),
        t("Dossier du jeu mis à jour", "Game folder updated", "Carpeta del juego actualizada", "Папка игры обновлена", "Spielordner aktualisiert", "游戏文件夹已更新", "ゲームフォルダを更新しました"),
        t("Nouveau dossier", "New folder", "Nueva carpeta", "Новая папка", "Neuer Ordner", "新建文件夹", "新しいフォルダ"),
        t("Modifier le dossier", "Edit folder", "Editar carpeta", "Изменить папку", "Ordner bearbeiten", "编辑文件夹", "フォルダを編集"),
        t("Supprimer le dossier secondaire ?", "Delete the secondary folder?", "¿Eliminar la carpeta secundaria?", "Удалить дополнительную папку?", "Sekundären Ordner löschen?", "删除次要文件夹？", "重複側のフォルダを削除しますか？"),
        t("Les sauvegardes sont traitées avant la suppression. Le dossier sera ensuite supprimé définitivement du téléphone.", "Saves are handled before deletion. The folder will then be permanently deleted from the phone.", "Los guardados se procesan antes de eliminar. Después se borrará la carpeta del teléfono.", "Сохранения будут обработаны до удаления. Затем папка будет навсегда удалена с телефона.", "Speicherstände werden vor dem Löschen verarbeitet. Danach wird der Ordner dauerhaft vom Telefon gelöscht.", "删除前会先处理存档，然后从手机永久删除文件夹。", "削除前にセーブを処理し、その後フォルダをスマートフォンから完全に削除します。"),
        t("Suppression physique irréversible après confirmation", "Physical deletion is irreversible after confirmation", "La eliminación física es irreversible tras confirmarla", "Физическое удаление необратимо после подтверждения", "Die physische Löschung ist nach Bestätigung irreversibel", "确认后无法撤销物理删除", "確認後の物理削除は取り消せません"),
        t("Conserver les fichiers et ignorer définitivement ce doublon", "Keep files and permanently ignore this duplicate", "Conservar los archivos e ignorar este duplicado definitivamente", "Сохранить файлы и навсегда игнорировать этот дубликат", "Dateien behalten und dieses Duplikat dauerhaft ignorieren", "保留文件并永久忽略此重复项", "ファイルを残し、この重複を完全に無視"),
        t("Choisissez l’exemplaire principal", "Choose the primary copy", "Elige la copia principal", "Выберите основной экземпляр", "Wähle das Haupt-Exemplar", "选择主副本", "メインのコピーを選択"),
        t("Comparer les doublons", "Compare duplicates", "Comparar duplicados", "Сравнить дубликаты", "Duplikate vergleichen", "比较重复项", "重複を比較"),
        t("Doublons détectés", "Detected duplicates", "Duplicados detectados", "Найденные дубликаты", "Erkannte Duplikate", "检测到的重复项", "検出した重複"),
        t("Exemplaire principal", "Primary copy", "Copia principal", "Основной экземпляр", "Haupt-Exemplar", "主副本", "メインのコピー"),
        t("Choisir comme principal", "Choose as primary", "Elegir como principal", "Выбрать основным", "Als Haupt-Exemplar auswählen", "设为主副本", "メインにする"),
        t("Garder le principal", "Keep primary", "Conservar el principal", "Оставить основной", "Haupt-Exemplar behalten", "保留主副本", "メインを保持"),
        t("Remplacer", "Replace", "Reemplazar", "Заменить", "Ersetzen", "替换", "置き換え"),
        t("Conserver les deux", "Keep both", "Conservar ambos", "Сохранить оба", "Beide behalten", "保留两者", "両方を保持"),
        t("En cas de même nom", "When names match", "Si tienen el mismo nombre", "При одинаковом имени", "Bei gleichem Namen", "名称相同时", "同名の場合"),
        t("Après cette fusion, les exemplaires restants seront reproposés un par un.", "After this merge, remaining copies will be offered one at a time.", "Después de esta fusión, las copias restantes se propondrán una a una.", "После объединения оставшиеся экземпляры будут предложены по одному.", "Nach diesem Zusammenführen werden die übrigen Exemplare einzeln angeboten.", "合并后会逐一处理剩余副本。", "この統合後、残りのコピーを1つずつ確認します。"),
        t("Retirer", "Remove", "Quitar", "Убрать", "Entfernen", "移除", "削除"),
        t("Nom copié", "Name copied", "Nombre copiado", "Имя скопировано", "Name kopiert", "名称已复制", "名前をコピーしました"),
        t("Nom du jeu copié", "Game name copied", "Nombre del juego copiado", "Название игры скопировано", "Spielname kopiert", "游戏名称已复制", "ゲーム名をコピーしました"),
        t("Rapport de scan", "Scan report", "Informe del escaneo", "Отчёт сканирования", "Scanbericht", "扫描报告", "スキャンレポート"),
        t("Dossiers", "Folders", "Carpetas", "Папки", "Ordner", "文件夹", "フォルダ"),
        t("Composants détectés", "Components detected", "Componentes detectados", "Обнаруженные компоненты", "Erkannte Komponenten", "检测到的组件", "検出したコンポーネント"),
        t("Version", "Version", "Versión", "Версия", "Version", "版本", "バージョン"),
        t("Récemment ajoutés", "Recently added", "Añadidos recientemente", "Недавно добавленные", "Kürzlich hinzugefügt", "最近添加", "最近追加"),
        t("Joués récemment", "Recently played", "Jugados recientemente", "Недавно запускались", "Kürzlich gespielt", "最近玩过", "最近プレイ"),
        t("Jamais joués", "Never played", "Nunca jugados", "Никогда не запускались", "Nie gespielt", "从未玩过", "未プレイ"),
        t("Jamais joué", "Never played", "Nunca jugado", "Никогда не запускалась", "Nie gespielt", "从未玩过", "未プレイ"),
        t("Jamais lancé", "Never launched", "Nunca iniciado", "Никогда не запускалась", "Nie gestartet", "从未启动", "未起動"),
        t("Détection locale des composants", "Local component detection", "Detección local de componentes", "Локальное обнаружение компонентов", "Lokale Komponentenerkennung", "本地组件检测", "コンポーネントをローカルで検出"),
        t("Installé", "Installed", "Instalado", "Установлено", "Installiert", "已安装", "インストール済み"),
        t("Requis par votre bibliothèque • non installé", "Required by your library • not installed", "Necesario para tu biblioteca • no instalado", "Требуется вашей библиотеке • не установлено", "Von deiner Bibliothek benötigt • nicht installiert", "你的资料库需要 • 未安装", "ライブラリで必要 • 未インストール"),
        t("Après installation ou mise à jour d’un plugin, fermez puis rouvrez ce gestionnaire pour relancer la détection.", "After installing or updating a plugin, close and reopen this manager to detect it again.", "Después de instalar o actualizar un plugin, cierra y vuelve a abrir este gestor para detectarlo de nuevo.", "После установки или обновления плагина закройте и снова откройте менеджер для повторного обнаружения.", "Schließe diesen Manager nach der Plugin-Installation oder -Aktualisierung und öffne ihn erneut.", "安装或更新插件后，请关闭并重新打开管理器以重新检测。", "プラグインのインストールまたは更新後、この管理画面を閉じて再度開くと再検出します。"),
        t("Sous-dossier — niveau", "Subfolder — level", "Subcarpeta — nivel", "Подпапка — уровень", "Unterordner — Ebene", "子文件夹 — 层级", "サブフォルダ — レベル"),
        t("Collections Astra", "Astra collections", "Colecciones de Astra", "Коллекции Astra", "Astra-Sammlungen", "Astra 收藏", "Astra コレクション"),
        t("Continuer vers les images", "Continue to images", "Continuar a las imágenes", "Перейти к изображениям", "Weiter zu Bildern", "继续选择图片", "画像へ進む"),
        t("Créer", "Create", "Crear", "Создать", "Erstellen", "创建", "作成"),
        t("Effacer la sélection", "Clear selection", "Borrar selección", "Очистить выбор", "Auswahl löschen", "Auswahl löschen", "選択を解除"),
        t("Ouvrir le dossier de sauvegarde", "Open backup folder", "Abrir carpeta de copia", "Открыть папку резервных копий", "Sicherungsordner öffnen", "打开备份文件夹", "バックアップフォルダを開く"),
        t("Ouvrir le thread F95Zone", "Open F95Zone thread", "Abrir el hilo de F95Zone", "Открыть тему F95Zone", "F95Zone-Thread öffnen", "打开 F95Zone 主题", "F95Zone スレッドを開く"),
        t("Remplace le catalogue par le contenu de l’archive", "Replaces the catalog with the archive contents", "Sustituye el catálogo por el contenido del archivo", "Заменяет каталог содержимым архива", "Ersetzt den Katalog durch den Inhalt des Archivs", "用归档内容替换目录", "アーカイブの内容でカタログを置き換えます"),
        t("Système", "System", "Sistema", "Система", "System", "系统", "システム"),
        t("Clair", "Light", "Claro", "Светлая", "Hell", "浅色", "ライト"),
        t("Sombre", "Dark", "Oscuro", "Тёмная", "Dunkel", "深色", "ダーク"),
        t("Configurer", "Configure", "Configurar", "Настроить", "Konfigurieren", "配置", "設定"),
        t("Favori", "Favorite", "Favorito", "Избранное", "Favorit", "收藏", "お気に入り"),
        t("Rescanner", "Rescan", "Volver a escanear", "Пересканировать", "Erneut scannen", "重新扫描", "再スキャン"),
        t("Image", "Image", "Imagen", "Изображение", "Bild", "图片", "画像"),
        t("JoiPlay", "JoiPlay", "JoiPlay", "JoiPlay", "JoiPlay", "JoiPlay", "JoiPlay"),
        t("Actions rapides", "Quick actions", "Acciones rápidas", "Быстрые действия", "Schnellaktionen", "快捷操作", "クイックアクション"),
        t("Changer de vue", "Change view", "Cambiar vista", "Сменить вид", "Ansicht wechseln", "切换视图", "表示を切り替え"),
        t("Chargement…", "Loading…", "Cargando…", "Загрузка…", "Laden…", "加载中…", "読み込み中…"),
        t("Nombre de colonnes", "Number of columns", "Número de columnas", "Количество столбцов", "Anzahl der Spalten", "列数", "列数"),
        t("Quitter la sélection", "Exit selection", "Salir de la selección", "Выйти из выбора", "Auswahl beenden", "退出选择", "選択を終了"),
        t("Réinitialiser les filtres", "Reset filters", "Restablecer filtros", "Сбросить фильтры", "Filter zurücksetzen", "重置筛选", "フィルターをリセット"),
        t("Au moins un", "At least one", "Al menos uno", "Хотя бы один", "Mindestens eines", "至少一条", "1つ以上"),
        t("Catégories de tags", "Tag categories", "Categorías de etiquetas", "Категории тегов", "Tag-Kategorien", "标签分类", "タグのカテゴリ"),
        t("Jeux manquants", "Missing games", "Juegos faltantes", "Пропавшие игры", "Fehlende Spiele", "缺失的游戏", "見つからないゲーム"),
        t("Dossier supprimé", "Folder deleted", "Carpeta eliminada", "Папка удалена", "Ordner gelöscht", "文件夹已删除", "フォルダを削除しました"),
        t("Sans jaquette", "No cover", "Sin carátula", "Без обложки", "Kein Cover", "无封面", "カバーなし"),
        t("Tag supprimé", "Tag deleted", "Etiqueta eliminada", "Тег удалён", "Tag gelöscht", "标签已删除", "タグを削除しました"),
        t("Vérifier à nouveau", "Check again", "Comprobar de nuevo", "Проверить снова", "Erneut prüfen", "重新检查", "もう一度確認"),
        t("Aucun tag. Utilisez + pour en créer un directement.", "No tags yet. Use + to create one directly.", "Sin etiquetas. Usa + para crear una directamente.", "Тегов нет. Нажмите +, чтобы создать тег.", "Keine Tags. Mit + direkt einen erstellen.", "暂无标签，可使用 + 直接创建。", "タグがありません。+ で直接作成できます。"),
        t("Ces réglages remplacent uniquement la détection automatique pour ce jeu.", "These settings only override automatic detection for this game.", "Estos ajustes solo sustituyen la detección automática para este juego.", "Эти настройки заменяют только автоматическое определение для этой игры.", "Diese Einstellungen überschreiben nur die automatische Erkennung für dieses Spiel.", "这些设置仅覆盖此游戏的自动检测。", "これらの設定は、このゲームの自動検出のみを上書きします。"),
        t("Créez des catégories puis classez plusieurs tags en une fois.", "Create categories, then organize several tags at once.", "Crea categorías y luego organiza varias etiquetas a la vez.", "Создавайте категории и распределяйте сразу несколько тегов.", "Erstelle Kategorien und ordne mehrere Tags auf einmal zu.", "创建分类，然后一次整理多个标签。", "カテゴリを作成し、複数のタグをまとめて整理します。"),
        t("Créez une collection avec vos critères.", "Create a collection with your criteria.", "Crea una colección con tus criterios.", "Создайте коллекцию по своим критериям.", "Erstelle eine Sammlung mit deinen Kriterien.", "根据您的条件创建收藏。", "条件を指定してコレクションを作成します。"),
        t("Fermer l'import F95Zone", "Close F95Zone import", "Cerrar la importación de F95Zone", "Закрыть импорт F95Zone", "F95Zone-Import schließen", "关闭 F95Zone 导入", "F95Zone の読み込みを閉じる"),
        t("Fermer la jaquette", "Close cover", "Cerrar carátula", "Закрыть обложку", "Cover schließen", "关闭封面", "カバーを閉じる"),
        t("Le dossier complet du jeu sera supprimé définitivement du téléphone.", "The game's full folder will be permanently deleted from the phone.", "La carpeta completa del juego se eliminará definitivamente del teléfono.", "Вся папка игры будет безвозвратно удалена с телефона.", "Der gesamte Spielordner wird dauerhaft vom Telefon gelöscht.", "游戏所在文件夹将被永久删除。", "ゲームのフォルダ全体がスマートフォンから完全に削除されます。"),
        t("Le jeu sera retiré et ignoré lors des prochains scans. Vous pourrez le réautoriser dans Paramètres > Jeux supprimés.", "The game will be removed and ignored during future scans. You can restore it in Settings > Deleted games.", "El juego se retirará y se ignorará en los próximos escaneos. Puedes restaurarlo en Ajustes > Juegos eliminados.", "Игра будет удалена и проигнорирована при следующих сканированиях. Вы сможете разрешить её снова в Настройках > Удалённые игры.", "Das Spiel wird entfernt und bei künftigen Scans ignoriert. Du kannst es unter Einstellungen > Gelöschte Spiele wieder zulassen.", "游戏将被移除并在下次扫描时忽略，可在设置 > 已删除游戏中恢复。", "ゲームは削除され、次回以降のスキャンで無視されます。設定 > 削除したゲーム から再許可できます。"),
        t("Les jeux et leurs fichiers seront conservés.", "Games and their files will be kept.", "Los juegos y sus archivos se conservarán.", "Игры и их файлы будут сохранены.", "Spiele und ihre Dateien bleiben erhalten.", "游戏及其文件会保留。", "ゲームとファイルは保持されます。"),
        t("Les tags seront conservés sans catégorie.", "Tags will be kept without a category.", "Las etiquetas se conservarán sin categoría.", "Теги будут сохранены без категории.", "Tags bleiben ohne Kategorie erhalten.", "标签将保留但不设分类。", "タグはカテゴリなしで保持されます。"),
        t("Préparation du recadrage…", "Preparing crop…", "Preparando el recorte…", "Подготовка обрезки…", "Zuschneiden wird vorbereitet…", "正在准备裁剪…", "トリミングを準備中…"),
        t("Supprimer la jaquette", "Delete cover", "Eliminar carátula", "Удалить обложку", "Cover löschen", "删除封面", "カバーを削除"),
        t("Plus de", "More than", "Más de", "Более", "Mehr als", "超过", "以上"),
        t("Utiliser le lien", "Use link", "Usar enlace", "Использовать ссылку", "Link verwenden", "使用链接", "リンクを使用"),
        t("Toutes les métadonnées sont déjà présentes", "All metadata is already up to date", "Todos los metadatos ya están actualizados", "Все метаданные уже актуальны", "Alle Metadaten sind bereits aktuell", "所有元数据都已是最新", "すべてのメタデータは最新です"),
        t("Aucune métadonnée trouvée — vérifiez votre connexion", "No metadata found — check your connection", "No se encontraron metadatos — comprueba tu conexión", "Метаданные не найдены — проверьте подключение", "Keine Metadaten gefunden — Verbindung prüfen", "未找到元数据 — 请检查网络连接", "メタデータが見つかりません — 接続を確認してください"),
        t("Se connecter à F95Zone", "Log in to F95Zone", "Iniciar sesión en F95Zone", "Войти в F95Zone", "Bei F95Zone anmelden", "登录 F95Zone", "F95Zone にログイン"),
        t("Se connecter", "Log in", "Iniciar sesión", "Войти", "Anmelden", "登录", "ログイン"),
        t("Se déconnecter", "Log out", "Cerrar sesión", "Выйти", "Abmelden", "退出登录", "ログアウト"),
        t("Utiliser cette session", "Use this session", "Usar esta sesión", "Использовать эту сессию", "Diese Sitzung verwenden", "使用此会话", "このセッションを使用"),
        t("Session F95Zone : non connectée", "F95Zone session: not logged in", "Sesión de F95Zone: no iniciada", "Сессия F95Zone: не выполнен вход", "F95Zone-Sitzung: nicht angemeldet", "F95Zone 会话：未登录", "F95Zone セッション：未ログイン"),
        t("Connectez-vous sur le site puis utilisez le bouton ci-dessous.", "Log in on the site, then use the button below.", "Inicia sesión en el sitio y usa el botón de abajo.", "Войдите на сайте, затем используйте кнопку ниже.", "Melde dich auf der Website an und nutze dann den Button unten.", "请在网站上登录，然后使用下方的按钮。", "サイトでログインし、下のボタンを使用してください。"),
        t("Aucune session F95Zone détectée. Connectez-vous d’abord sur le site.", "No F95Zone session detected. Log in on the site first.", "No se detectó ninguna sesión de F95Zone. Inicia sesión primero en el sitio.", "Сессия F95Zone не обнаружена. Сначала войдите на сайт.", "Keine F95Zone-Sitzung erkannt. Melde dich zuerst auf der Website an.", "未检测到 F95Zone 会话，请先在网站上登录。", "F95Zone セッションが見つかりません。先にサイトでログインしてください。"),
        t("Session F95Zone enregistrée", "F95Zone session saved", "Sesión de F95Zone guardada", "Сессия F95Zone сохранена", "F95Zone-Sitzung gespeichert", "F95Zone 会话已保存", "F95Zone セッションを保存しました"),
        t("Session F95Zone supprimée", "F95Zone session removed", "Sesión de F95Zone eliminada", "Сессия F95Zone удалена", "F95Zone-Sitzung entfernt", "F95Zone 会话已删除", "F95Zone セッションを削除しました"),
        t("Session F95Zone expirée ou invalide — reconnectez-vous.", "F95Zone session expired or invalid — log in again.", "Sesión de F95Zone caducada o inválida — vuelve a iniciar sesión.", "Сессия F95Zone истекла или недействительна — войдите снова.", "F95Zone-Sitzung abgelaufen oder ungültig — erneut anmelden.", "F95Zone 会话已过期或无效 — 请重新登录。", "F95Zone セッションが期限切れか無効です — 再ログインしてください。"),
        t("Mises à jour", "Updates", "Actualizaciones", "Обновления", "Updates", "更新", "アップデート"),
        t("Mises à jour de jeux", "Game updates", "Actualizaciones de juegos", "Обновления игр", "Spiel-Updates", "游戏更新", "ゲームの更新"),
        t("Vérifier les mises à jour", "Check for updates", "Buscar actualizaciones", "Проверить обновления", "Nach Updates suchen", "检查更新", "更新を確認"),
        t("Aucune mise à jour disponible", "No updates available", "No hay actualizaciones disponibles", "Обновлений нет", "Keine Updates verfügbar", "暂无可用更新", "利用可能な更新はありません"),
        t("Aucun jeu lié à un thread F95Zone", "No game linked to an F95Zone thread", "Ningún juego enlazado a un hilo de F95Zone", "Нет игр, связанных с темой F95Zone", "Kein Spiel mit F95Zone-Thread verknüpft", "没有关联 F95Zone 主题的游戏", "F95Zone スレッドにリンクされたゲームがありません"),
        t("Arrêter la synchronisation", "Stop syncing", "Detener la sincronización", "Остановить синхронизацию", "Synchronisierung stoppen", "停止同步", "同期を停止"),
        t("Synchronisation arrêtée", "Sync stopped", "Sincronización detenida", "Синхронизация остановлена", "Synchronisierung gestoppt", "同步已停止", "同期を停止しました"),
        t("Version inconnue", "Unknown version", "Versión desconocida", "Неизвестная версия", "Unbekannte Version", "未知版本", "不明なバージョン"),
        t("Compte F95Zone (optionnel)", "F95Zone account (optional)", "Cuenta de F95Zone (opcional)", "Аккаунт F95Zone (необязательно)", "F95Zone-Konto (optional)", "F95Zone 账户（可选）", "F95Zone アカウント（任意）"),
        t("Connectez-vous pour accéder au contenu réservé aux membres et récupérer les versions des jeux.", "Log in to access member-only content and fetch game versions.", "Inicia sesión para acceder al contenido exclusivo para miembros y obtener las versiones de los juegos.", "Войдите, чтобы получить доступ к контенту для участников и версиям игр.", "Melde dich an, um Mitglieder-Inhalte zu sehen und Spielversionen abzurufen.", "登录以访问会员专属内容并获取游戏版本。", "ログインすると会員限定コンテンツとゲームのバージョンを取得できます。"),
        t("Arrêter", "Stop", "Detener", "Остановить", "Stoppen", "停止", "停止"),
        t("Ignorer", "Skip", "Omitir", "Пропустить", "Überspringen", "跳过", "スキップ"),
        t("Fusions", "Merges", "Fusiones", "Слияния", "Zusammenführungen", "合并", "統合"),
        t("Fusions de tags", "Tag merges", "Fusiones de etiquetas", "Слияния тегов", "Tag-Zusammenführungen", "标签合并", "タグの統合"),
        t("Aucune fusion à vérifier", "No merges to review", "No hay fusiones que revisar", "Нет слияний для проверки", "Keine Zusammenführungen zu prüfen", "没有需要检查的合并", "確認する統合はありません"),
        t("Historique des modifications", "Modification history", "Historial de cambios", "История изменений", "Änderungsverlauf", "修改历史", "変更履歴"),
        t("Fusions de tags, suppressions et modifications", "Tag merges, deletions and changes", "Fusiones de etiquetas, eliminaciones y cambios", "Слияния тегов, удаления и изменения", "Tag-Zusammenführungen, Löschungen und Änderungen", "标签合并、删除和修改", "タグの統合、削除、変更"),
        t("Aucun événement enregistré", "No events recorded", "No hay eventos registrados", "События не записаны", "Keine Ereignisse aufgezeichnet", "暂无记录事件", "記録されたイベントはありません"),
        t("Chaque lancement", "Every launch", "Cada inicio", "При каждом запуске", "Bei jedem Start", "每次启动", "起動のたびに"),
        t("1 jour", "1 day", "1 día", "1 день", "1 Tag", "1 天", "1日"),
        t("3 jours", "3 days", "3 días", "3 дня", "3 Tage", "3 天", "3日"),
        t("7 jours", "7 days", "7 días", "7 дней", "7 Tage", "7 天", "7日"),
        t("15 jours", "15 days", "15 días", "15 дней", "15 Tage", "15 天", "15日"),
        t("30 jours", "30 days", "30 días", "30 дней", "30 Tage", "30 天", "30日"),
        t("Marquer comme vu", "Mark as seen", "Marcar como visto", "Отметить как просмотренное", "Als gesehen markieren", "标记为已查看", "確認済みにする"),
        t("Mise à jour effectuée ?", "Update done?", "¿Actualización realizada?", "Обновление выполнено?", "Update durchgeführt?", "更新完成了吗？", "アップデートは完了しましたか？"),
        t("Mise à jour effectuée", "Update done", "Actualización realizada", "Обновление выполнено", "Update durchgeführt", "更新已完成", "アップデート完了"),
        t("Outils", "Tools", "Herramientas", "Инструменты", "Werkzeuge", "工具", "ツール"),
        t("Historique", "History", "Historial", "История", "Verlauf", "历史", "履歴"),
        t("Historique de jeux", "Play history", "Historial de partidas", "История игр", "Spielverlauf", "游戏历史", "プレイ履歴"),
        t("Verrouillage biometrique", "Biometric lock", "Bloqueo biometrico", "Биометрическая блокировка", "Biometrische Sperre", "生物识别锁定", "生体認証ロック"),
        t("Verrouillage par code", "PIN lock", "Bloqueo por codigo", "Блокировка кодом", "Codesperre", "密码锁定", "PINロック"),
        t("Desactiver le code", "Disable PIN", "Desactivar el codigo", "Отключить код", "Code deaktivieren", "关闭密码", "PINを無効化"),
        t("Astra est verrouille", "Astra is locked", "Astra esta bloqueado", "Astra заблокирован", "Astra ist gesperrt", "Astra 已锁定", "Astra はロック中です"),
        t("Deverrouiller", "Unlock", "Desbloquear", "Разблокировать", "Entsperren", "解锁", "ロック解除"),
        t("Deverrouiller par biometrie", "Unlock with biometrics", "Desbloquear con biometria", "Разблокировать биометрией", "Mit Biometrie entsperren", "使用生物识别解锁", "生体認証で解除"),
        t("Dossier des sauvegardes", "Save folder", "Carpeta de guardados", "Папка сохранений", "Spielstandordner", "存档文件夹", "セーブフォルダ"),
        t("Editer une sauvegarde", "Edit a save", "Editar una partida", "Изменить сохранение", "Spielstand bearbeiten", "编辑存档", "セーブを編集"),
        t("Gerer les mods", "Manage mods", "Gestionar mods", "Управлять модами", "Mods verwalten", "管理模组", "MODを管理"),
        t("Installer", "Install", "Instalar", "Установить", "Installieren", "安装", "インストール"),
        t("Desinstaller", "Uninstall", "Desinstalar", "Удалить", "Deinstallieren", "卸载", "アンインストール"),
        t("Importer un ZIP", "Import a ZIP", "Importar un ZIP", "Импортировать ZIP", "ZIP importieren", "导入 ZIP", "ZIPを読み込む"),
        t("Simple", "Simple", "Simple", "Простой", "Einfach", "简易", "シンプル"),
        t("Avance", "Advanced", "Avanzado", "Расширенный", "Erweitert", "高级", "詳細"),
        t("Argent", "Money", "Dinero", "Деньги", "Geld", "金钱", "所持金"),
        t("Niveau", "Level", "Nivel", "Уровень", "Stufe", "等级", "レベル"),
        t("Experience", "Experience", "Experiencia", "Опыт", "Erfahrung", "经验", "経験値"),
        t("Securite et historique", "Security and history", "Seguridad e historial", "Безопасность и история", "Sicherheit und Verlauf", "安全与历史", "セキュリティと履歴"),
        t("Verrouiller en arriere-plan", "Lock when backgrounded", "Bloquear en segundo plano", "Блокировать в фоне", "Im Hintergrund sperren", "切到后台时锁定", "バックグラウンドでロック")
    )

    private val translations = AppLanguage.entries.associateWith { language ->
        catalog.associate { it.source to it.value(language) }
    }

    fun text(source: String, selectedLanguage: AppLanguage = language): String {
        val current = selectedLanguage
        if (current == AppLanguage.FRENCH) return source
        translations[current]?.get(source)?.let { return it }
        translateDynamic(source, current)?.let { return it }
        // English is the intentional fallback for newly added strings in non-French locales.
        return translations[AppLanguage.ENGLISH]?.get(source) ?: source
    }

    private val dynamicPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun dynamicPattern(pattern: String): Regex = dynamicPatterns.getOrPut(pattern) { Regex(pattern) }

    private fun translateDynamic(source: String, language: AppLanguage): String? {
        fun count(pattern: Regex, value: (Int) -> String): String? = pattern.matchEntire(source)?.groupValues?.get(1)?.toIntOrNull()?.let(value)
        count(dynamicPattern("(\\d+) colonnes")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number columns"; AppLanguage.SPANISH -> "$number columnas"; AppLanguage.RUSSIAN -> "$number столбцов"; AppLanguage.GERMAN -> "$number Spalten"; AppLanguage.CHINESE -> "$number 列"; AppLanguage.JAPANESE -> "$number 列"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) jeux")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number games"; AppLanguage.SPANISH -> "$number juegos"; AppLanguage.RUSSIAN -> "$number игр"; AppLanguage.GERMAN -> "$number Spiele"; AppLanguage.CHINESE -> "$number 个游戏"; AppLanguage.JAPANESE -> "$number 本のゲーム"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) jeu\\(x\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number game(s)"; AppLanguage.SPANISH -> "$number juego(s)"; AppLanguage.RUSSIAN -> "$number игр"; AppLanguage.GERMAN -> "$number Spiel(e)"; AppLanguage.CHINESE -> "$number 个游戏"; AppLanguage.JAPANESE -> "$number 本のゲーム"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) tag\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number tag(s)"; AppLanguage.SPANISH -> "$number etiqueta(s)"; AppLanguage.RUSSIAN -> "$number тег(ов)"; AppLanguage.GERMAN -> "$number Tag(s)"; AppLanguage.CHINESE -> "$number 个标签"; AppLanguage.JAPANESE -> "$number 個のタグ"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) dossiers")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number folders"; AppLanguage.SPANISH -> "$number carpetas"; AppLanguage.RUSSIAN -> "$number папок"; AppLanguage.GERMAN -> "$number Ordner"; AppLanguage.CHINESE -> "$number 个文件夹"; AppLanguage.JAPANESE -> "$number 個のフォルダ"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) sous-dossier\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number subfolder(s)"; AppLanguage.SPANISH -> "$number subcarpeta(s)"; AppLanguage.RUSSIAN -> "$number подпапок"; AppLanguage.GERMAN -> "$number Unterordner"; AppLanguage.CHINESE -> "$number 个子文件夹"; AppLanguage.JAPANESE -> "$number 個のサブフォルダ"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) groupe\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number group(s)"; AppLanguage.SPANISH -> "$number grupo(s)"; AppLanguage.RUSSIAN -> "$number групп"; AppLanguage.GERMAN -> "$number Gruppe(n)"; AppLanguage.CHINESE -> "$number 个组"; AppLanguage.JAPANESE -> "$number グループ"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) sélectionné\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number selected"; AppLanguage.SPANISH -> "$number seleccionados"; AppLanguage.RUSSIAN -> "Выбрано: $number"; AppLanguage.GERMAN -> "$number ausgewählt"; AppLanguage.CHINESE -> "已选择 $number 项"; AppLanguage.JAPANESE -> "$number 件を選択"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) tags importés")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number tags imported"; AppLanguage.SPANISH -> "$number etiquetas importadas"; AppLanguage.RUSSIAN -> "Импортировано тегов: $number"; AppLanguage.GERMAN -> "$number Tags importiert"; AppLanguage.CHINESE -> "已导入 $number 个标签"; AppLanguage.JAPANESE -> "$number 個のタグを読み込みました"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) composants détectés")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number components detected"; AppLanguage.SPANISH -> "$number componentes detectados"; AppLanguage.RUSSIAN -> "Обнаружено компонентов: $number"; AppLanguage.GERMAN -> "$number Komponenten erkannt"; AppLanguage.CHINESE -> "检测到 $number 个组件"; AppLanguage.JAPANESE -> "$number 個のコンポーネントを検出"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        dynamicPattern("^(\\d+) sur (\\d+) sélectionné\\(s\\)$").matchEntire(source)?.let { match ->
            val selected = match.groupValues[1]; val total = match.groupValues[2]
            return when (language) {
                AppLanguage.ENGLISH -> "$selected of $total selected"; AppLanguage.SPANISH -> "$selected de $total seleccionados"; AppLanguage.RUSSIAN -> "Выбрано $selected из $total"; AppLanguage.GERMAN -> "$selected von $total ausgewählt"; AppLanguage.CHINESE -> "已选择 $selected / $total"; AppLanguage.JAPANESE -> "$total 件中 $selected 件を選択"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^(\\d+) jeux trouvés, (\\d+) ajoutés$").matchEntire(source)?.let { match ->
            val found = match.groupValues[1]; val added = match.groupValues[2]
            return when (language) {
                AppLanguage.ENGLISH -> "$found games found, $added added"; AppLanguage.SPANISH -> "$found juegos encontrados, $added añadidos"; AppLanguage.RUSSIAN -> "Найдено игр: $found, добавлено: $added"; AppLanguage.GERMAN -> "$found Spiele gefunden, $added hinzugefügt"; AppLanguage.CHINESE -> "找到 $found 个游戏，添加 $added 个"; AppLanguage.JAPANESE -> "$found 本のゲームを検出、$added 本を追加"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^(\\d+) jeux détectés$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "$number games detected"; AppLanguage.SPANISH -> "$number juegos detectados"; AppLanguage.RUSSIAN -> "Обнаружено игр: $number"; AppLanguage.GERMAN -> "$number Spiele erkannt"; AppLanguage.CHINESE -> "检测到 $number 个游戏"; AppLanguage.JAPANESE -> "$number 本のゲームを検出"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^(\\d+) jeu\\(x\\) classé\\(s\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "$number game(s) organized"; AppLanguage.SPANISH -> "$number juego(s) organizados"; AppLanguage.RUSSIAN -> "Игр распределено: $number"; AppLanguage.GERMAN -> "$number Spiel(e) organisiert"; AppLanguage.CHINESE -> "已整理 $number 个游戏"; AppLanguage.JAPANESE -> "$number 本のゲームを整理"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^(\\d+) jeu\\(x\\) mis à jour$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "$number game(s) updated"; AppLanguage.SPANISH -> "$number juego(s) actualizados"; AppLanguage.RUSSIAN -> "Игр обновлено: $number"; AppLanguage.GERMAN -> "$number Spiel(e) aktualisiert"; AppLanguage.CHINESE -> "已更新 $number 个游戏"; AppLanguage.JAPANESE -> "$number 本のゲームを更新"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Tags ajoutés à (\\d+) jeu\\(x\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "Tags added to $number game(s)"; AppLanguage.SPANISH -> "Etiquetas añadidas a $number juego(s)"; AppLanguage.RUSSIAN -> "Теги добавлены к играм: $number"; AppLanguage.GERMAN -> "Tags zu $number Spiel(en) hinzugefügt"; AppLanguage.CHINESE -> "已为 $number 个游戏添加标签"; AppLanguage.JAPANESE -> "$number 本のゲームにタグを追加"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Actualisation terminée pour (\\d+) jeu\\(x\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "Refresh finished for $number game(s)"; AppLanguage.SPANISH -> "Actualización terminada para $number juego(s)"; AppLanguage.RUSSIAN -> "Обновление завершено для игр: $number"; AppLanguage.GERMAN -> "Aktualisierung für $number Spiel(e) abgeschlossen"; AppLanguage.CHINESE -> "已完成 $number 个游戏的刷新"; AppLanguage.JAPANESE -> "$number 本のゲームの更新が完了"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Sauvegarde créée : (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { name ->
            return when (language) {
                AppLanguage.ENGLISH -> "Backup created: $name"; AppLanguage.SPANISH -> "Copia creada: $name"; AppLanguage.RUSSIAN -> "Резервная копия создана: $name"; AppLanguage.GERMAN -> "Sicherung erstellt: $name"; AppLanguage.CHINESE -> "已创建备份：$name"; AppLanguage.JAPANESE -> "バックアップを作成しました: $name"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Sous-dossier — niveau (\\d+)$").matchEntire(source)?.groupValues?.get(1)?.let { level ->
            return when (language) {
                AppLanguage.ENGLISH -> "Subfolder — level $level"; AppLanguage.SPANISH -> "Subcarpeta — nivel $level"; AppLanguage.RUSSIAN -> "Подпапка — уровень $level"; AppLanguage.GERMAN -> "Unterordner — Ebene $level"; AppLanguage.CHINESE -> "子文件夹 — 层级 $level"; AppLanguage.JAPANESE -> "サブフォルダ — レベル $level"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Automatique \\((.+)\\)$").matchEntire(source)?.groupValues?.get(1)?.let { engine ->
            return when (language) {
                AppLanguage.ENGLISH -> "Automatic ($engine)"; AppLanguage.SPANISH -> "Automático ($engine)"; AppLanguage.RUSSIAN -> "Автоматически ($engine)"; AppLanguage.GERMAN -> "Automatisch ($engine)"; AppLanguage.CHINESE -> "自动（$engine）"; AppLanguage.JAPANESE -> "自動（$engine）"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Rechercher sur (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { engine ->
            return when (language) {
                AppLanguage.ENGLISH -> "Search on $engine"; AppLanguage.SPANISH -> "Buscar en $engine"; AppLanguage.RUSSIAN -> "Поиск в $engine"; AppLanguage.GERMAN -> "Auf $engine suchen"; AppLanguage.CHINESE -> "在 $engine 中搜索"; AppLanguage.JAPANESE -> "$engine で検索"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Filtres \\((\\d+)\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "Filters ($number)"; AppLanguage.SPANISH -> "Filtros ($number)"; AppLanguage.RUSSIAN -> "Фильтры ($number)"; AppLanguage.GERMAN -> "Filter ($number)"; AppLanguage.CHINESE -> "筛选（$number）"; AppLanguage.JAPANESE -> "フィルター（$number）"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Tout \\((\\d+)\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "All ($number)"; AppLanguage.SPANISH -> "Todo ($number)"; AppLanguage.RUSSIAN -> "Все ($number)"; AppLanguage.GERMAN -> "Alle ($number)"; AppLanguage.CHINESE -> "全部（$number）"; AppLanguage.JAPANESE -> "すべて（$number）"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Tags \\((\\d+)\\)$").matchEntire(source)?.groupValues?.get(1)?.let { number ->
            return when (language) {
                AppLanguage.ENGLISH -> "Tags ($number)"; AppLanguage.SPANISH -> "Etiquetas ($number)"; AppLanguage.RUSSIAN -> "Теги ($number)"; AppLanguage.GERMAN -> "Tags ($number)"; AppLanguage.CHINESE -> "标签（$number）"; AppLanguage.JAPANESE -> "タグ（$number）"; AppLanguage.FRENCH -> source
            }
        }
        count(dynamicPattern("(\\d+) jeu\\(x\\) restant\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number game(s) remaining"; AppLanguage.SPANISH -> "$number juego(s) restante(s)"; AppLanguage.RUSSIAN -> "Осталось игр: $number"; AppLanguage.GERMAN -> "$number Spiel(e) übrig"; AppLanguage.CHINESE -> "剩余 $number 个游戏"; AppLanguage.JAPANESE -> "残り $number 本のゲーム"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) nouveau\\(x\\) jeu\\(x\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number new game(s)"; AppLanguage.SPANISH -> "$number juego(s) nuevo(s)"; AppLanguage.RUSSIAN -> "Новых игр: $number"; AppLanguage.GERMAN -> "$number neues Spiel / neue Spiele"; AppLanguage.CHINESE -> "$number 个新游戏"; AppLanguage.JAPANESE -> "$number 本の新しいゲーム"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) tag\\(s\\) sélectionné\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number tag(s) selected"; AppLanguage.SPANISH -> "$number etiqueta(s) seleccionada(s)"; AppLanguage.RUSSIAN -> "Выбрано тегов: $number"; AppLanguage.GERMAN -> "$number Tag(s) ausgewählt"; AppLanguage.CHINESE -> "已选择 $number 个标签"; AppLanguage.JAPANESE -> "$number 個のタグを選択"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) jeu\\(x\\) ignoré\\(s\\) pendant les scans")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number game(s) ignored during scans"; AppLanguage.SPANISH -> "$number juego(s) ignorado(s) durante los escaneos"; AppLanguage.RUSSIAN -> "Игнорировано игр при сканировании: $number"; AppLanguage.GERMAN -> "$number Spiel(e) beim Scannen ignoriert"; AppLanguage.CHINESE -> "扫描时忽略了 $number 个游戏"; AppLanguage.JAPANESE -> "スキャンで $number 本のゲームを無視"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) exemplaires")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number copies"; AppLanguage.SPANISH -> "$number copias"; AppLanguage.RUSSIAN -> "$number копий"; AppLanguage.GERMAN -> "$number Exemplare"; AppLanguage.CHINESE -> "$number 份副本"; AppLanguage.JAPANESE -> "$number コピー"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("(\\d+) lancements")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number launches"; AppLanguage.SPANISH -> "$number lanzamientos"; AppLanguage.RUSSIAN -> "$number запусков"; AppLanguage.GERMAN -> "$number Starts"; AppLanguage.CHINESE -> "$number 次启动"; AppLanguage.JAPANESE -> "$number 回の起動"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        dynamicPattern("^Version (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { version ->
            return when (language) {
                AppLanguage.ENGLISH -> "Version $version"; AppLanguage.SPANISH -> "Versión $version"; AppLanguage.RUSSIAN -> "Версия $version"; AppLanguage.GERMAN -> "Version $version"; AppLanguage.CHINESE -> "版本 $version"; AppLanguage.JAPANESE -> "バージョン $version"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Configurer (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { title ->
            return when (language) {
                AppLanguage.ENGLISH -> "Configure $title"; AppLanguage.SPANISH -> "Configurar $title"; AppLanguage.RUSSIAN -> "Настроить: $title"; AppLanguage.GERMAN -> "$title konfigurieren"; AppLanguage.CHINESE -> "配置 $title"; AppLanguage.JAPANESE -> "$title を設定"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Jaquette de (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { title ->
            return when (language) {
                AppLanguage.ENGLISH -> "Cover for $title"; AppLanguage.SPANISH -> "Carátula de $title"; AppLanguage.RUSSIAN -> "Обложка: $title"; AppLanguage.GERMAN -> "Cover von $title"; AppLanguage.CHINESE -> "$title 的封面"; AppLanguage.JAPANESE -> "$title のカバー"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^Étape (\\d+) sur 3 · (.+)$").matchEntire(source)?.let { match ->
            val number = match.groupValues[1]; val title = match.groupValues[2]
            return when (language) {
                AppLanguage.ENGLISH -> "Step $number of 3 · $title"; AppLanguage.SPANISH -> "Paso $number de 3 · $title"; AppLanguage.RUSSIAN -> "Шаг $number из 3 · $title"; AppLanguage.GERMAN -> "Schritt $number von 3 · $title"; AppLanguage.CHINESE -> "第 $number/3 步 · $title"; AppLanguage.JAPANESE -> "ステップ $number/3 · $title"; AppLanguage.FRENCH -> source
            }
        }
        count(dynamicPattern("(\\d+) mise\\(s\\) à jour trouvée\\(s\\)")) { number -> when (language) {
            AppLanguage.ENGLISH -> "$number update(s) found"; AppLanguage.SPANISH -> "$number actualización(es) encontrada(s)"; AppLanguage.RUSSIAN -> "Найдено обновлений: $number"; AppLanguage.GERMAN -> "$number Update(s) gefunden"; AppLanguage.CHINESE -> "找到 $number 个更新"; AppLanguage.JAPANESE -> "$number 件の更新を検出"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        dynamicPattern("Avez-vous effectué la mise à jour vers la version (.+?) ?\\?").matchEntire(source)?.groupValues?.get(1)?.let { version -> when (language) {
            AppLanguage.ENGLISH -> "Did you install version $version?"; AppLanguage.SPANISH -> "¿Has instalado la versión $version?"; AppLanguage.RUSSIAN -> "Вы установили версию $version?"; AppLanguage.GERMAN -> "Hast du Version $version installiert?"; AppLanguage.CHINESE -> "你是否已安装 $version 版本？"; AppLanguage.JAPANESE -> "$version をインストールしましたか？"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        dynamicPattern("Nouvelle version disponible : (.+)").matchEntire(source)?.groupValues?.get(1)?.let { version -> when (language) {
            AppLanguage.ENGLISH -> "New version available: $version"; AppLanguage.SPANISH -> "Nueva versión disponible: $version"; AppLanguage.RUSSIAN -> "Доступна новая версия: $version"; AppLanguage.GERMAN -> "Neue Version verfügbar: $version"; AppLanguage.CHINESE -> "新版本可用：$version"; AppLanguage.JAPANESE -> "新しいバージョンが利用可能：$version"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        count(dynamicPattern("Ressemblance : (\\d+) %")) { number -> when (language) {
            AppLanguage.ENGLISH -> "Similarity: $number%"; AppLanguage.SPANISH -> "Similitud: $number%"; AppLanguage.RUSSIAN -> "Сходство: $number%"; AppLanguage.GERMAN -> "Ähnlichkeit: $number%"; AppLanguage.CHINESE -> "相似度：$number%"; AppLanguage.JAPANESE -> "類似度：$number%"; AppLanguage.FRENCH -> source
        } }?.let { return it }
        dynamicPattern("^Connecté en tant que (.+)$").matchEntire(source)?.groupValues?.get(1)?.let { user ->
            return when (language) {
                AppLanguage.ENGLISH -> "Logged in as $user"; AppLanguage.SPANISH -> "Conectado como $user"; AppLanguage.RUSSIAN -> "Вход выполнен: $user"; AppLanguage.GERMAN -> "Angemeldet als $user"; AppLanguage.CHINESE -> "已登录为 $user"; AppLanguage.JAPANESE -> "$user としてログイン"; AppLanguage.FRENCH -> source
            }
        }
        dynamicPattern("^(.+) \\((\\d+)\\)$").matchEntire(source)?.let { match ->
            val prefix = text(match.groupValues[1], language); val number = match.groupValues[2]
            return "$prefix ($number)"
        }
        return null
    }
}

/** Drop-in text wrapper used by the Compose UI so existing user data is never translated. */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current
) {
    val language = LocalAppLanguage.current
    MaterialText(
        text = AppLocalizer.text(text, language),
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        style = style
    )
}
