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
                            << "  noise:adaptive      Enable Adaptive mode\n"
                            << "  info                Print device status (battery, noise mode, ...) as JSON\n";
        return 1;
    }

    QLocalSocket socket;
    socket.connectToServer("app_server");

    if (!socket.waitForConnected(500)) {
        QTextStream(stderr) << "Could not connect to librepods (is it running?)\n";
        return 1;
    }

    QByteArray command(argv[1]);
    socket.write(command);
    socket.flush();
    socket.waitForBytesWritten(200);

    if (command == "info") {
        QByteArray reply;
        while (socket.waitForReadyRead(1000))
            reply += socket.readAll();
        reply += socket.readAll();
        if (reply.isEmpty()) {
            QTextStream(stderr) << "No reply from librepods\n";
            return 1;
        }
        QTextStream(stdout) << reply;
        return 0;
    }

    socket.disconnectFromServer();
    return 0;
}
