package io.tvos;

/** Settings text and stable screen IDs; no drawing, preferences, actions or focus state. */
final class SettingsContent {
    private SettingsContent() { }
    static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, GENERAL = 6, WIDGETS = 7, SYSTEM = 8, ABOUT = 9, SCREENSAVER = 10, WEATHER = 11, DATE_TIME = 12, MENU_BUTTONS = 13, UPDATES = 14, TOP_PANEL = 15, ACCESSIBILITY = 16, RESET = 17, VPN_SETTINGS = 18, APPS_DOWNLOADS = 19, CATEGORY_EDIT = 20;
    static final int PICK_CITY = 0, PICK_VPN = -1;

    static String screenTitle(int screen, int settingsGroup, int pickFor, boolean russian) {
        switch (screen) {
            case GENERAL: return russian ? "Основные" : "General";
            case WIDGETS: return russian ? "Виджеты" : "Widgets";
            case WEATHER: return tr(russian, "Погода", "Weather");
            case DATE_TIME: return tr(russian, "Дата", "Date");
            case SYSTEM: return russian ? "Система" : "System";
            case RESET: return tr(russian, "Сброс", "Reset");
            case TOP_PANEL: return tr(russian, "Верхняя панель", "Menu Bar");
            case ACCESSIBILITY: return tr(russian, "Универсальный доступ", "Accessibility");
            case APPS_DOWNLOADS: return tr(russian, "Приложения", "Apps");
            case MENU_BUTTONS: return tr(russian, "Кнопки", "Buttons");
            case UPDATES: return tr(russian, "Обновления ПО", "Software Updates");
            case ABOUT: return russian ? "Об этом устройстве" : "About";
            case SCREENSAVER: return tr(russian, "Заставка", "Screen Saver");
            case HOME_CHOICE: return tr(russian, "Домашний экран", "Default Launcher");
            case VPN_SETTINGS: return "VPN";
            case HOME_SETTINGS: return settingsGroup == WIDGETS ? (russian ? "Котировки" : "Quotes") : settingsGroup == SYSTEM ? (russian ? "Категории" : "Categories") : (russian ? "Основные" : "General");
            case REMOTE_KEYS: return tr(russian, "Пульты и устройства", "Remotes and Devices");
            case SHELF_EDIT: return "Dock";
            case CATEGORY_EDIT: return tr(russian, "Категории", "Categories");
            case PICKER: return pickFor == PICK_VPN ? "VPN" : pickFor == PICK_CITY ? (russian ? "Регион" : "Region") : (russian ? "Выбор приложения" : "Choose app");
            default: return russian ? "Настройки" : "Settings";
        }
    }

    static String[] menuLabels(int screen, boolean russian) {
        switch (screen) {
            case GENERAL: return new String[] {tr(russian, "Об этом устройстве", "About"), tr(russian, "Оформление", "Appearance"), tr(russian, "Язык", "Language"), tr(russian, "Регион", "Region"), tr(russian, "Дата и время", "Date & Time"), tr(russian, "Температура", "Temperature"), tr(russian, "Формат времени", "Time Format"), tr(russian, "Щелчки при навигации", "Navigation Clicks")};
            case WIDGETS: return new String[] {tr(russian, "Погода", "Weather"), tr(russian, "Котировки", "Quotes"), tr(russian, "Дата", "Date")};
            case DATE_TIME: return new String[] {tr(russian, "Показывать на верхней панели", "Show in Menu Bar")};
            case WEATHER: return new String[] {tr(russian, "Показывать на верхней панели", "Show in Menu Bar"), tr(russian, "Показывать на нижней панели", "Show in Bottom Menu")};
            case UPDATES: return new String[] {tr(russian, "Обновление ПО", "Update Software"), tr(russian, "Автоматическое обновление", "Automatically Update")};
            case SYSTEM: return new String[] {tr(russian, "Обновления ПО", "Software Updates"), tr(russian, "Сброс", "Reset"), tr(russian, "Перезагрузить tvOS", "Restart tvOS")};
            case RESET: return new String[] {tr(russian, "Очистить кеш", "Clear cache"), tr(russian, "Сброс tvOS", "Reset tvOS")};
            case TOP_PANEL: return new String[] {tr(russian, "Категории", "Categories"), tr(russian, "Кнопки", "Buttons")};
            case APPS_DOWNLOADS: return new String[] {tr(russian, "Загрузить AirPlay", "Download AirPlay"), tr(russian, "Загрузить Apple Music", "Download Apple Music"), tr(russian, "Загрузить TizenTube", "Download TizenTube"), tr(russian, "Загрузить INCY", "Download INCY"), tr(russian, "Загрузить Seena", "Download Seena"), tr(russian, "Загрузить Lampa", "Download Lampa"), tr(russian, "Загрузить KinoPub", "Download KinoPub")};
            case ACCESSIBILITY: return new String[] {tr(russian, "Уменьшение прозрачности", "Reduce Transparency"), tr(russian, "Уменьшение анимаций", "Reduce Motion"), tr(russian, "Домашний экран", "Default Launcher"), tr(russian, "Настройки ТВ", "TV Settings")};
            case VPN_SETTINGS: return new String[] {tr(russian, "Показывать на верхней панели", "Show in Menu Bar"), tr(russian, "VPN-служба", "VPN")};
            case SCREENSAVER: return new String[] {tr(russian, "Источник", "Current Selection"), tr(russian, "Показывать часы", "Show Clock"), tr(russian, "Показывать место съёмки", "Show Location"), tr(russian, "Показывать погоду", "Show Weather"), tr(russian, "Предпросмотр", "Preview")};
            case ABOUT: return new String[] {tr(russian, "Имя", "Name"), tr(russian, "Модель", "Model"), tr(russian, "Серийный номер", "Serial Number"), "tvOS", tr(russian, "ОС", "OS"), tr(russian, "Разрешение", "Resolution"), tr(russian, "Имя сети Wi-Fi", "Wi-Fi Network Name"), tr(russian, "IP-адрес", "IP Address")};
            default: return new String[] {tr(russian, "Основные", "General"), tr(russian, "Заставка", "Screen Saver"), "AirPlay", tr(russian, "Пульты и устройства", "Remotes and Devices"), tr(russian, "Универсальный доступ", "Accessibility"), tr(russian, "Приложения", "Apps"), "Dock", tr(russian, "Верхняя панель", "Menu Bar"), tr(russian, "Виджеты", "Widgets"), tr(russian, "Система", "System")};
        }
    }

    static String description(int screen, int focus, int pickFor, int settingsGroup,
                          int menuButtonCount, boolean russian) {
        String info = null;
        switch (screen) {
            case MAIN:
                if (focus == 2) info = tr(russian, "Для AirPlay нужно дополнительное приложение.\nУстановите его в разделе «Приложения».",
                        "AirPlay requires an additional app.\nInstall it from the Apps section.");
                else if (focus == 6) info = tr(russian, "Настройка приложений на панели Dock.", "Choose the apps shown in the Dock.");
                break;
            case GENERAL:
                if (focus == 3) info = tr(russian, "Город для прогноза погоды.", "The city used for weather forecasts.");
                break;
            case SCREENSAVER:
                if (focus == 3) info = tr(russian, "Город для погоды указывается в пункте «Регион».",
                        "Set the weather city in Region.");
                break;
            case APPS_DOWNLOADS:
                info = appDownloadDescription(focus, russian);
                break;
            case HOME_CHOICE:
                info = tr(russian, "tvOS — без рекламы, с новой заставкой. Встроенный — заводской главный экран телевизора. Переключение занимает несколько секунд; экран может мигать.",
                        "tvOS has no ads and a new screen saver. Built-in uses the TV's original home screen. Switching can take a few seconds.");
                break;
            case CATEGORY_EDIT:
                info = tr(russian, "Разделы верхней панели.\nOK — включить или выключить, стрелки — изменить порядок.",
                        "Menu Bar categories.\nOK turns a category on or off; left and right change its order.");
                break;
            case SHELF_EDIT:
                info = tr(russian, "Настройка приложений на панели Dock.\nOK — добавить или убрать, стрелки — изменить порядок.",
                        "Choose the apps shown in the Dock.\nOK adds or removes an app; left and right change its order.");
                break;
            case PICKER:
                info = pickFor == PICK_CITY
                        ? tr(russian, "Город для прогноза погоды.", "The city used for weather forecasts.")
                        : tr(russian, "Нажмите OK для выбора. Назад — отмена.", "Press OK to select. Back to cancel.");
                break;
            case HOME_SETTINGS:
                info = tr(russian, "OK или стрелки изменяют выбранный параметр.", "Press OK or the arrow keys to change a setting.");
                if (screen == HOME_SETTINGS && settingsGroup == SYSTEM)
                    info = tr(russian, "Включайте и выключайте разделы главного экрана.", "Enable or disable home screen sections.");
                break;
            case TOP_PANEL:
                if (focus == 1) info = tr(russian, "Настройка кнопок быстрого доступа на верхней панели.", "Configure quick-access buttons in the Menu Bar.");
                break;
            case MENU_BUTTONS:
                info = focus == menuButtonCount
                        ? tr(russian, "Выберите приложение для кнопки VPN.", "Choose the app opened by the VPN button.")
                        : tr(russian, "OK — включить или выключить, стрелки — изменить порядок.",
                                "OK turns a button on or off; left and right change its order.");
                break;
            case RESET:
                info = tr(russian, "Очистка кеша не удаляет настройки. Сброс tvOS удалит все данные приложения.", "Clearing cache preserves settings. Reset tvOS erases all app data.");
                break;
            case REMOTE_KEYS:
                info = tr(russian, "Выберите назначение кнопок пульта. Некоторые кнопки могут управляться самим телевизором.",
                        "Choose remote button actions. Some buttons may be controlled by the TV.");
                break;
        }
        return info;
    }

    private static String appDownloadDescription(int index, boolean russian) {
        switch (index) {
            case 0: return tr(russian, "Трансляция экрана, видео и музыки\nс iPhone, iPad и Mac на телевизор.",
                    "Share your screen, videos and music\nfrom iPhone, iPad or Mac to your TV.");
            case 1: return tr(russian, "Музыка, альбомы и плейлисты на телевизоре.\nСторонний клиент; нужна подписка Apple Music.",
                    "Music, albums and playlists on your TV.\nA third-party client; Apple Music subscription required.");
            case 2: return tr(russian, "Альтернативный клиент YouTube\nс интерфейсом для телевизора и управлением с пульта.",
                    "An alternative YouTube client\nwith a TV interface and remote control support.");
            case 3: return tr(russian, "VPN-клиент для подключения к вашим серверам.\nИмпорт конфигураций и подписок по ссылке.",
                    "A VPN client for connecting to your own servers.\nImport configurations and subscription links.");
            case 4: return tr(russian, "Фильмы, сериалы и мультфильмы\nв приложении с управлением с пульта.",
                    "Movies, series and cartoons\nin an app designed for your TV remote.");
            case 5: return tr(russian, "Каталог фильмов и сериалов для телевизора.\nИсточники просмотра настраиваются отдельно.",
                    "A movie and series catalog for your TV.\nPlayback sources are configured separately.");
            case 6: return tr(russian, "Фильмы и сериалы из сервиса KinoPub.\nДля просмотра нужен аккаунт с доступом.",
                    "Movies and series from KinoPub.\nAn account with viewing access is required.");
            default: return null;
        }
    }

    private static String tr(boolean russian, String ru, String en) { return russian ? ru : en; }
}
