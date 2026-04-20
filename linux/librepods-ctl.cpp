#include <QCoreApplication>
#include <QLocalSocket>
#include <QTextStream>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);

    if (argc < 2) {
        QTextStream(stderr) << "Usage: librepods-ctl <command>\n"
                            << "Commands:\n"
                            << "  noise:off           Disable noise control\n"
                            << "  noise:anc           Enable Active Noise Cancellation\n"
                            << "  noise:transparency  Enable Transparency mode\n"
                            << "  noise:adaptive      Enable Adaptive mode\n";
        return 1;
    }

    QLocalSocket socket;
    socket.connectToServer("app_server");

    if (!socket.waitForConnected(500)) {
        QTextStream(stderr) << "Could not connect to librepods (is it running?)\n";
        return 1;
    }

    socket.write(QByteArray(argv[1]));
    socket.flush();
    socket.waitForBytesWritten(200);
    socket.disconnectFromServer();
    return 0;
}
