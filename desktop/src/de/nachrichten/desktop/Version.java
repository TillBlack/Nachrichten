package de.nachrichten.desktop;

/** Version der Windows-Version (unabhängig von der Android-Versionsnummer). */
final class Version {
    /** Fortlaufende Nummer – bei jedem Windows-Update erhöhen. */
    static final int CODE = 2;
    /** Anzeigename, Format X.Y.Z (für das Windows-Installationsprogramm). */
    static final String NAME = "1.0.1";

    private Version() {}
}
