package com.photon.remote;

/** Protokół połączenia z serwerem zdalnym. */
public enum RemoteProtocol {
    FTP("FTP (bez szyfrowania)", 21),
    FTPS("FTPS — jawny TLS (FTPES)", 21),
    FTPS_IMPLICIT("FTPS — niejawny TLS", 990),
    SFTP("SFTP (SSH)", 22);

    private final String label;
    private final int defaultPort;

    RemoteProtocol(String label, int defaultPort) {
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String label() {
        return label;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public boolean isFtp() {
        return this != SFTP;
    }

    public String scheme() {
        return switch (this) {
            case FTP -> "ftp";
            case FTPS, FTPS_IMPLICIT -> "ftps";
            case SFTP -> "sftp";
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
