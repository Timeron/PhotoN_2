package com.photon.remote;

/**
 * Połączenie z serwerem zdalnym, zdefiniowane raz dla całej aplikacji. W zakładkach wskazuje się je
 * tylko nazwą: {@code [Nazwa]/folder/na/serwerze}.
 *
 * @param name     nazwa połączenia (unikalna, widoczna w zakładkach)
 * @param port     port (0 = domyślny dla protokołu)
 * @param password hasło — zapisywane zwykłym tekstem w pliku {@code servers.json}
 * @param passive  tryb pasywny FTP (zalecany za routerem/NAT-em)
 * @param hostKey  odcisk klucza serwera SFTP zapamiętany przy pierwszym połączeniu (null = jeszcze nieznany)
 */
public record RemoteServer(String name, RemoteProtocol protocol, String host, int port, String user,
                           String password, boolean passive, String hostKey) {

    public RemoteServer {
        name = name == null ? "" : name.trim();
        protocol = protocol == null ? RemoteProtocol.FTP : protocol;
        host = host == null ? "" : host.trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        hostKey = hostKey == null || hostKey.isBlank() ? null : hostKey;
    }

    public static RemoteServer empty() {
        return new RemoteServer("", RemoteProtocol.FTP, "", 0, "", "", true, null);
    }

    public int effectivePort() {
        return port > 0 ? port : protocol.defaultPort();
    }

    /** Adres do wyświetlenia (bez hasła), np. {@code sftp://jan@nas.local:22}. */
    public String address() {
        return protocol.scheme() + "://" + (user.isEmpty() ? "" : user + "@") + host + ":" + effectivePort();
    }

    public RemoteServer withHostKey(String key) {
        return new RemoteServer(name, protocol, host, port, user, password, passive, key);
    }

    /** Czy zmiana dotyczy sposobu łączenia (wtedy otwarte połączenia trzeba zamknąć). */
    public boolean sameConnection(RemoteServer other) {
        return other != null && protocol == other.protocol && host.equals(other.host)
                && effectivePort() == other.effectivePort() && user.equals(other.user)
                && password.equals(other.password) && passive == other.passive
                && java.util.Objects.equals(hostKey, other.hostKey);
    }

    /** Błąd w konfiguracji albo null. */
    public String validate() {
        if (name.isEmpty()) {
            return "Podaj nazwę połączenia.";
        }
        if (name.contains("[") || name.contains("]") || name.contains("/") || name.contains("\\")) {
            return "Nazwa połączenia nie może zawierać znaków [ ] / \\.";
        }
        if (host.isEmpty()) {
            return "Podaj adres serwera.";
        }
        if (host.contains("://") || host.contains("/")) {
            return "Adres serwera podaj bez protokołu i folderu (np. nas.local albo 192.168.1.10).";
        }
        if (port < 0 || port > 65535) {
            return "Port musi być liczbą od 1 do 65535.";
        }
        if (protocol == RemoteProtocol.SFTP && user.isEmpty()) {
            return "Podaj użytkownika (SFTP wymaga logowania).";
        }
        return null;
    }
}
