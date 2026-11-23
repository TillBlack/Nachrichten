package de.nachrichten.app;

import java.util.Locale;

/** Texte: Englisch, Deutsch, Spanisch, Russisch, Kroatisch. */
final class T {
    static final String[] CODES = {"en", "de", "es", "ru", "hr"};
    static final String[] NAMES = {"English", "Deutsch", "Español", "Русский", "Hrvatski"};
    static int lang = 0;

    static String s(String... v) { return v[lang]; }
    static Locale locale() { return Locale.forLanguageTag(CODES[lang]); }

    // Start
    static String welcome() { return s("Welcome", "Willkommen", "Bienvenido", "Добро пожаловать", "Dobro došli"); }
    static String welcomeText() { return s(
            "Your messages are end-to-end encrypted. There is no account and no phone number. Your identity is a secret key that is created on this phone.",
            "Deine Nachrichten sind Ende-zu-Ende-verschlüsselt. Es gibt kein Konto und keine Telefonnummer. Deine Identität ist ein geheimer Schlüssel, der auf diesem Handy erstellt wird.",
            "Tus mensajes están cifrados de extremo a extremo. No hay cuenta ni número de teléfono. Tu identidad es una clave secreta que se crea en este móvil.",
            "Твои сообщения защищены сквозным шифрованием. Нет ни аккаунта, ни номера телефона. Твоя личность — это секретный ключ, который создаётся на этом телефоне.",
            "Tvoje poruke su šifrirane s kraja na kraj. Nema računa ni broja telefona. Tvoj identitet je tajni ključ koji se stvara na ovom mobitelu."); }
    static String yourName() { return s("Your name", "Dein Name", "Tu nombre", "Твоё имя", "Tvoje ime"); }
    static String nameInfo() { return s("Shown to people who scan your QR code.", "Wird Leuten angezeigt, die deinen QR-Code scannen.", "Se muestra a quien escanee tu código QR.", "Видно тем, кто сканирует твой QR-код.", "Vide ga oni koji skeniraju tvoj QR kod."); }
    static String start() { return s("Start", "Loslegen", "Empezar", "Начать", "Započni"); }
    static String restore() { return s("Restore from backup key", "Mit Sicherungsschlüssel wiederherstellen", "Restaurar con clave de respaldo", "Восстановить из резервного ключа", "Vrati iz sigurnosnog ključa"); }
    static String restoreHint() { return s("Backup key (nsec1…)", "Sicherungsschlüssel (nsec1…)", "Clave de respaldo (nsec1…)", "Резервный ключ (nsec1…)", "Sigurnosni ključ (nsec1…)"); }
    static String invalidKey() { return s("This key is not valid.", "Dieser Schlüssel ist ungültig.", "Esta clave no es válida.", "Этот ключ недействителен.", "Ovaj ključ nije valjan."); }

    // Liste
    static String chats() { return s("Chats", "Chats", "Chats", "Чаты", "Razgovori"); }
    static String noChats() { return s("No chats yet.\nTap + to add someone.", "Noch keine Chats.\nTippe auf +, um jemanden hinzuzufügen.", "Aún no hay chats.\nToca + para añadir a alguien.", "Пока нет чатов.\nНажми +, чтобы кого-нибудь добавить.", "Još nema razgovora.\nDodirni + da nekoga dodaš."); }
    static String noMessagesYet() { return s("No messages yet", "Noch keine Nachrichten", "Aún no hay mensajes", "Сообщений пока нет", "Još nema poruka"); }
    static String you() { return s("You: ", "Du: ", "Tú: ", "Вы: ", "Ti: "); }
    static String offline() { return s("Connecting…", "Verbinde…", "Conectando…", "Подключение…", "Povezivanje…"); }

    // Kontakt hinzufügen / eigener Code
    static String addContact() { return s("Add contact", "Kontakt hinzufügen", "Añadir contacto", "Добавить контакт", "Dodaj kontakt"); }
    static String scanQr() { return s("Scan QR code", "QR-Code scannen", "Escanear código QR", "Сканировать QR-код", "Skeniraj QR kod"); }
    static String enterCode() { return s("Enter text code", "Textcode eingeben", "Introducir código", "Ввести текстовый код", "Unesi tekstni kod"); }
    static String myCode() { return s("My code", "Mein Code", "Mi código", "Мой код", "Moj kod"); }
    static String myCodeInfo() { return s("Let a friend scan this QR code, or send them your text code.", "Lass einen Freund diesen QR-Code scannen oder schick ihm deinen Textcode.", "Deja que un amigo escanee este código QR o envíale tu código de texto.", "Дай другу отсканировать этот QR-код или отправь ему текстовый код.", "Neka prijatelj skenira ovaj QR kod ili mu pošalji svoj tekstni kod."); }
    static String textCode() { return s("Text code", "Textcode", "Código de texto", "Текстовый код", "Tekstni kod"); }
    static String codeHint() { return s("npub1…", "npub1…", "npub1…", "npub1…", "npub1…"); }
    static String name() { return s("Name", "Name", "Nombre", "Имя", "Ime"); }
    static String add() { return s("Add", "Hinzufügen", "Añadir", "Добавить", "Dodaj"); }
    static String invalidCode() { return s("This code is not valid.", "Dieser Code ist ungültig.", "Este código no es válido.", "Этот код недействителен.", "Ovaj kod nije valjan."); }
    static String ownCode() { return s("That is your own code.", "Das ist dein eigener Code.", "Ese es tu propio código.", "Это твой собственный код.", "To je tvoj vlastiti kod."); }
    static String copy() { return s("Copy", "Kopieren", "Copiar", "Копировать", "Kopiraj"); }
    static String copied() { return s("Copied", "Kopiert", "Copiado", "Скопировано", "Kopirano"); }
    static String share() { return s("Share", "Teilen", "Compartir", "Поделиться", "Podijeli"); }
    static String shareText() { return s("Add me in Nachrichten:", "Füg mich in Nachrichten hinzu:", "Agrégame en Nachrichten:", "Добавь меня в Nachrichten:", "Dodaj me u Nachrichten:"); }

    // Scanner
    static String scanHint() { return s("Point the camera at a QR code", "Halte die Kamera auf einen QR-Code", "Apunta la cámara a un código QR", "Наведи камеру на QR-код", "Usmjeri kameru na QR kod"); }
    static String cameraDenied() { return s("Camera access is needed to scan QR codes.", "Für das Scannen braucht die App Zugriff auf die Kamera.", "Se necesita acceso a la cámara para escanear.", "Для сканирования нужен доступ к камере.", "Za skeniranje je potreban pristup kameri."); }

    // Chat
    static String messageHint() { return s("Message", "Nachricht", "Mensaje", "Сообщение", "Poruka"); }
    static String chatEmpty() { return s("Messages are end-to-end encrypted.\nSay hello!", "Nachrichten sind Ende-zu-Ende-verschlüsselt.\nSag hallo!", "Los mensajes están cifrados de extremo a extremo.\n¡Saluda!", "Сообщения защищены сквозным шифрованием.\nПоздоровайся!", "Poruke su šifrirane s kraja na kraj.\nPozdravi!"); }
    static String rename() { return s("Rename", "Umbenennen", "Renombrar", "Переименовать", "Preimenuj"); }
    static String showCode() { return s("Show their code", "Code anzeigen", "Ver su código", "Показать код", "Prikaži kod"); }
    static String deleteChat() { return s("Delete chat", "Chat löschen", "Eliminar chat", "Удалить чат", "Obriši razgovor"); }
    static String deleteChatQ() { return s("Delete this chat and its messages on this phone?", "Diesen Chat und seine Nachrichten auf diesem Handy löschen?", "¿Eliminar este chat y sus mensajes en este móvil?", "Удалить этот чат и его сообщения на этом телефоне?", "Obrisati ovaj razgovor i poruke na ovom mobitelu?"); }
    static String deleteMsgQ() { return s("Delete this message on this phone?", "Diese Nachricht auf diesem Handy löschen?", "¿Eliminar este mensaje en este móvil?", "Удалить это сообщение на этом телефоне?", "Obrisati ovu poruku na ovom mobitelu?"); }
    static String today() { return s("Today", "Heute", "Hoy", "Сегодня", "Danas"); }
    static String yesterday() { return s("Yesterday", "Gestern", "Ayer", "Вчера", "Jučer"); }
    static String pendingInfo() { return s("Waiting to send", "Wird gesendet, sobald online", "Pendiente de envío", "Ожидает отправки", "Čeka slanje"); }

    // Antworten und Anhänge
    static String reply() { return s("Reply", "Antworten", "Responder", "Ответить", "Odgovori"); }
    static String youName() { return s("You", "Du", "Tú", "Вы", "Ti"); }
    static String photo() { return s("Photo", "Foto", "Foto", "Фото", "Fotografija"); }
    static String fileLabel() { return s("File", "Datei", "Archivo", "Файл", "Datoteka"); }
    static String attachPhoto() { return s("Photo", "Foto", "Foto", "Фото", "Fotografija"); }
    static String attachFile() { return s("File", "Datei", "Archivo", "Файл", "Datoteka"); }
    static String receiving() { return s("Receiving", "Wird empfangen", "Recibiendo", "Получение", "Primanje"); }
    static String tooLarge() { return s("Too large. Files can be at most 1 MB.", "Zu groß. Dateien dürfen höchstens 1 MB groß sein.", "Demasiado grande. Máximo 1 MB.", "Слишком большой. Максимум 1 МБ.", "Prevelika. Najviše 1 MB."); }
    static String readError() { return s("Could not read the file.", "Die Datei konnte nicht gelesen werden.", "No se pudo leer el archivo.", "Не удалось прочитать файл.", "Datoteku nije moguće pročitati."); }
    static String saveFile() { return s("Save", "Speichern", "Guardar", "Сохранить", "Spremi"); }
    static String saved() { return s("Saved", "Gespeichert", "Guardado", "Сохранено", "Spremljeno"); }
    static String openWith() { return s("Open with…", "Öffnen mit…", "Abrir con…", "Открыть с помощью…", "Otvori s…"); }
    static String noApp() { return s("No app can open this file.", "Keine App kann diese Datei öffnen.", "Ninguna app puede abrir este archivo.", "Нет приложения для этого файла.", "Nijedna aplikacija ne može otvoriti ovu datoteku."); }
    static String unsupported() { return s("Message from a newer version", "Nachricht aus einer neueren Version", "Mensaje de una versión más nueva", "Сообщение из новой версии", "Poruka iz novije verzije"); }
    static String encrypted() { return s("Encrypted", "Verschlüsselt", "Cifrado", "Зашифровано", "Šifrirano"); }

    // Allgemein
    static String save() { return s("Save", "Speichern", "Guardar", "Сохранить", "Spremi"); }
    static String cancel() { return s("Cancel", "Abbrechen", "Cancelar", "Отмена", "Odustani"); }
    static String delete() { return s("Delete", "Löschen", "Eliminar", "Удалить", "Obriši"); }
    static String close() { return s("Close", "Schließen", "Cerrar", "Закрыть", "Zatvori"); }

    // Einstellungen
    static String settings() { return s("Settings", "Einstellungen", "Ajustes", "Настройки", "Postavke"); }
    static String language() { return s("Language", "Sprache", "Idioma", "Язык", "Jezik"); }
    static String theme() { return s("Theme", "Design", "Tema", "Тема", "Tema"); }
    static String dark() { return s("Dark", "Dunkel", "Oscuro", "Тёмная", "Tamna"); }
    static String light() { return s("Light", "Hell", "Claro", "Светлая", "Svijetla"); }
    static String background() { return s("Receive when app is closed", "Auch bei geschlossener App empfangen", "Recibir con la app cerrada", "Получать при закрытом приложении", "Primaj i kad je aplikacija zatvorena"); }
    static String backgroundInfo() { return s(
            "Keeps a connection open and shows a small permanent notification. Without it, new messages arrive when you open the app.",
            "Hält eine Verbindung offen und zeigt dafür eine kleine dauerhafte Benachrichtigung. Ohne das kommen neue Nachrichten an, sobald du die App öffnest.",
            "Mantiene una conexión abierta y muestra una pequeña notificación permanente. Sin esto, los mensajes llegan al abrir la app.",
            "Держит соединение открытым и показывает небольшое постоянное уведомление. Без этого сообщения придут, когда откроешь приложение.",
            "Drži vezu otvorenom i prikazuje malu stalnu obavijest. Bez toga poruke stižu kad otvoriš aplikaciju."); }
    static String relays() { return s("Relays", "Relays", "Relés", "Релеи", "Releji"); }
    static String relaysInfo() { return s(
            "Relays pass on your messages. They only see encrypted text, but they can see who writes to whom and when.",
            "Relays leiten deine Nachrichten weiter. Sie sehen nur verschlüsselten Text, aber sie sehen, wer wem wann schreibt.",
            "Los relés reenvían tus mensajes. Solo ven texto cifrado, pero pueden ver quién escribe a quién y cuándo.",
            "Релеи пересылают твои сообщения. Они видят только зашифрованный текст, но видят, кто кому и когда пишет.",
            "Releji prosljeđuju tvoje poruke. Vide samo šifrirani tekst, ali vide tko kome i kada piše."); }
    static String addRelay() { return s("Add relay", "Relay hinzufügen", "Añadir relé", "Добавить релей", "Dodaj relej"); }
    static String relayHint() { return s("wss://…", "wss://…", "wss://…", "wss://…", "wss://…"); }
    static String resetRelays() { return s("Reset to default", "Auf Standard zurücksetzen", "Restablecer", "Сбросить", "Vrati zadano"); }
    static String backup() { return s("Backup key", "Sicherungsschlüssel", "Clave de respaldo", "Резервный ключ", "Sigurnosni ključ"); }
    static String backupInfo() { return s(
            "With this key you can move to a new phone. Anyone who has it can read your messages and write as you. Never share it.",
            "Mit diesem Schlüssel kannst du auf ein neues Handy umziehen. Wer ihn hat, kann deine Nachrichten lesen und als du schreiben. Gib ihn niemandem.",
            "Con esta clave puedes pasar a un móvil nuevo. Quien la tenga puede leer tus mensajes y escribir como tú. No la compartas nunca.",
            "С этим ключом можно перейти на новый телефон. Тот, у кого он есть, может читать твои сообщения и писать от твоего имени. Никому его не давай.",
            "S ovim ključem možeš prijeći na novi mobitel. Tko ga ima, može čitati tvoje poruke i pisati kao ti. Nikad ga ne dijeli."); }
    static String showKey() { return s("Show backup key", "Sicherungsschlüssel anzeigen", "Mostrar clave", "Показать ключ", "Prikaži ključ"); }

    static String newMessage() { return s("New message", "Neue Nachricht", "Mensaje nuevo", "Новое сообщение", "Nova poruka"); }
    static String hideStatus() { return s("Hide permanent notification", "Dauerhafte Benachrichtigung ausblenden", "Ocultar notificación permanente", "Скрыть постоянное уведомление", "Sakrij stalnu obavijest"); }
    static String hideStatusInfo() { return s(
            "Android requires it while the app receives in the background. Switch off \"Background connection\" on the next screen. Messages still arrive and are still notified.",
            "Android verlangt sie, solange die App im Hintergrund empfängt. Schalte auf dem nächsten Bildschirm \"Hintergrundverbindung\" aus. Nachrichten kommen trotzdem an und werden weiter gemeldet.",
            "Android la exige mientras la app recibe en segundo plano. Desactiva \"Conexión en segundo plano\" en la siguiente pantalla. Los mensajes siguen llegando y avisando.",
            "Android требует его, пока приложение получает в фоне. Выключи \"Фоновое соединение\" на следующем экране. Сообщения всё равно будут приходить с уведомлениями.",
            "Android je zahtijeva dok aplikacija prima u pozadini. Na sljedećem zaslonu isključi \"Pozadinska veza\". Poruke i dalje stižu s obavijestima."); }

    // Profil
    static String profilePicture() { return s("Profile picture", "Profilbild", "Foto de perfil", "Фото профиля", "Profilna slika"); }
    static String choose() { return s("Choose", "Auswählen", "Elegir", "Выбрать", "Odaberi"); }
    static String remove() { return s("Remove", "Entfernen", "Quitar", "Убрать", "Ukloni"); }
    static String profileInfo() { return s(
            "Your name and picture are sent encrypted to your contacts, never to anyone else.",
            "Name und Bild werden verschlüsselt nur an deine Kontakte geschickt, an niemanden sonst.",
            "Tu nombre y foto se envían cifrados solo a tus contactos.",
            "Имя и фото отправляются в зашифрованном виде только твоим контактам.",
            "Ime i slika šalju se šifrirano samo tvojim kontaktima."); }

    // Updates
    static String updateTitle() { return s("Update available", "Update verfügbar", "Actualización disponible", "Доступно обновление", "Dostupno ažuriranje"); }
    static String updateText(long v, long cur) { return s(
            "Version " + v + " is ready (you have " + cur + "). Your chats stay as they are.",
            "Version " + v + " ist da (du hast " + cur + "). Deine Chats bleiben erhalten.",
            "La versión " + v + " está lista (tienes la " + cur + "). Tus chats se conservan.",
            "Версия " + v + " готова (у тебя " + cur + "). Чаты сохранятся.",
            "Verzija " + v + " je spremna (imaš " + cur + "). Razgovori ostaju."); }
    static String updateHow() { return s(
            "Android downloads it for you. When it is done, tap the \"Nachrichten\" download notification and then Install.",
            "Android lädt es für dich herunter. Danach auf die Download-Benachrichtigung \"Nachrichten\" und dann auf Installieren tippen.",
            "Android lo descarga por ti. Luego toca la notificación de descarga \"Nachrichten\" y después Instalar.",
            "Android скачает его. Затем нажми на уведомление о загрузке \"Nachrichten\" и на Установить.",
            "Android ga preuzima za tebe. Zatim dodirni obavijest o preuzimanju \"Nachrichten\" pa Instaliraj."); }
    static String updateHowObtainium() { return s(
            "\"Update\" opens Nachrichten in Obtainium. The first time, tap Add there.",
            "\"Aktualisieren\" öffnet Nachrichten in Obtainium. Beim ersten Mal dort auf Hinzufügen tippen.",
            "\"Actualizar\" abre Nachrichten en Obtainium. La primera vez, toca Añadir allí.",
            "\"Обновить\" откроет Nachrichten в Obtainium. В первый раз нажми там Добавить.",
            "\"Ažuriraj\" otvara Nachrichten u Obtainiumu. Prvi put tamo dodirni Dodaj."); }
    static String downloadStarted() { return s("Download started. Tap the notification when it is done.", "Download läuft. Tippe danach auf die Benachrichtigung.", "Descarga iniciada. Toca la notificación al terminar.", "Загрузка началась. Потом нажми на уведомление.", "Preuzimanje je počelo. Zatim dodirni obavijest."); }
    static String downloadTap() { return s("Tap to install", "Zum Installieren antippen", "Toca para instalar", "Нажми для установки", "Dodirni za instalaciju"); }
    static String helperTitle() { return s("Easier updates with Obtainium?", "Bequemere Updates mit Obtainium?", "¿Actualizaciones más fáciles con Obtainium?", "Обновления проще с Obtainium?", "Lakša ažuriranja s Obtainiumom?"); }
    static String helperText() { return s(
            "Obtainium is a free, open-source app that installs updates straight from GitHub. If you have it, \"Update\" opens Nachrichten in Obtainium. Without it, Android downloads updates for you and you tap the notification to install.",
            "Obtainium ist eine kostenlose, quelloffene App, die Updates direkt von GitHub installiert. Wenn du sie hast, öffnet \"Aktualisieren\" Nachrichten in Obtainium. Ohne Obtainium lädt Android Updates herunter, und du tippst zum Installieren auf die Benachrichtigung.",
            "Obtainium es una app gratuita y de código abierto que instala actualizaciones directamente desde GitHub. Si la tienes, \"Actualizar\" abre Nachrichten en Obtainium. Sin ella, Android descarga la actualización y tocas la notificación para instalar.",
            "Obtainium — бесплатное приложение с открытым кодом, которое ставит обновления прямо с GitHub. Если оно есть, \"Обновить\" откроет Nachrichten в Obtainium. Без него Android скачает обновление, и ты нажмёшь на уведомление для установки.",
            "Obtainium je besplatna aplikacija otvorenog koda koja instalira ažuriranja izravno s GitHuba. Ako je imaš, \"Ažuriraj\" otvara Nachrichten u Obtainiumu. Bez nje Android preuzima ažuriranje, a ti dodirneš obavijest za instalaciju."); }
    static String helperInstall() { return s("Get Obtainium", "Obtainium holen", "Obtener Obtainium", "Скачать Obtainium", "Preuzmi Obtainium"); }
    static String github() { return s("Developer on GitHub", "Entwickler auf GitHub", "Desarrollador en GitHub", "Разработчик на GitHub", "Razvojni programer na GitHubu"); }
    static String typing() { return s("typing…", "schreibt…", "escribiendo…", "печатает…", "piše…"); }
    static String statusSent() { return s("Sent", "Gesendet", "Enviado", "Отправлено", "Poslano"); }
    static String statusDelivered() { return s("Delivered", "Zugestellt", "Entregado", "Доставлено", "Dostavljeno"); }
    static String statusRead() { return s("Read", "Gelesen", "Leído", "Прочитано", "Pročitano"); }
    static String readReceipts() { return s("Read receipts", "Lesebestätigungen", "Confirmaciones de lectura", "Отчёты о прочтении", "Potvrde čitanja"); }
    static String readReceiptsInfo() { return s(
            "If off, others don't see when you read their messages (they still see \"delivered\").",
            "Wenn aus, sehen andere nicht, wann du ihre Nachrichten gelesen hast (\"zugestellt\" sehen sie weiterhin).",
            "Si está desactivado, los demás no ven cuándo lees sus mensajes (siguen viendo \"entregado\").",
            "Если выключено, другие не видят, когда ты прочитал их сообщения (\"доставлено\" видно).",
            "Ako je isključeno, drugi ne vide kad si pročitao njihove poruke (i dalje vide \"dostavljeno\")."); }
    static String showTyping() { return s("Show when I'm typing", "Anzeigen, wenn ich schreibe", "Mostrar cuando escribo", "Показывать, что я печатаю", "Prikaži kad pišem"); }
    static String later() { return s("Later", "Später", "Más tarde", "Позже", "Kasnije"); }
    static String updateNow() { return s("Update", "Aktualisieren", "Actualizar", "Обновить", "Ažuriraj"); }
    static String downloading() { return s("Downloading…", "Wird geladen…", "Descargando…", "Загрузка…", "Preuzimanje…"); }
    static String updateFailed() { return s("Update failed. Try again later.", "Update fehlgeschlagen. Versuch es später nochmal.", "La actualización falló. Inténtalo más tarde.", "Не удалось обновить. Попробуй позже.", "Ažuriranje nije uspjelo. Pokušaj kasnije."); }
    static String allowInstall() { return s("Allow \"Install unknown apps\" for Nachrichten, then tap Update again.", "Erlaube \"Unbekannte Apps installieren\" für Nachrichten und tippe dann nochmal auf Aktualisieren.", "Permite \"Instalar apps desconocidas\" para Nachrichten y vuelve a tocar Actualizar.", "Разреши \"Установку неизвестных приложений\" для Nachrichten и снова нажми Обновить.", "Dopusti \"Instaliranje nepoznatih aplikacija\" za Nachrichten pa ponovno dodirni Ažuriraj."); }
    static String autoUpdate() { return s("Check for updates at start", "Beim Start nach Updates suchen", "Buscar actualizaciones al iniciar", "Проверять обновления при запуске", "Provjeri ažuriranja pri pokretanju"); }
    static String autoUpdateInfo(long v) { return s(
            "Asks GitHub for a newer version. Installed version: " + v,
            "Fragt bei GitHub nach einer neueren Version. Installierte Version: " + v,
            "Pregunta a GitHub por una versión nueva. Versión instalada: " + v,
            "Спрашивает у GitHub о новой версии. Установлена версия: " + v,
            "Pita GitHub za noviju verziju. Instalirana verzija: " + v); }

    // Benachrichtigungen
    static String chMessages() { return s("Messages", "Nachrichten", "Mensajes", "Сообщения", "Poruke"); }
    static String chService() { return s("Background connection", "Hintergrundverbindung", "Conexión en segundo plano", "Фоновое соединение", "Pozadinska veza"); }
    static String serviceTitle() { return s("Receiving in background", "Empfang im Hintergrund", "Recibiendo en segundo plano", "Приём в фоне", "Primanje u pozadini"); }
    static String relaysConnected(int n, int total) {
        switch (lang) {
            case 1: return n + " von " + total + " Relays verbunden";
            case 2: return n + " de " + total + " relés conectados";
            case 3: return "Подключено релеев: " + n + " из " + total;
            case 4: return "Povezano releja: " + n + " od " + total;
            default: return n + " of " + total + " relays connected";
        }
    }
}
