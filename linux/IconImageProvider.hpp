// LibrePods HiiT: renders the Lucide icons and device illustrations in any color.
#pragma once

#include <QQuickImageProvider>
#include <QSvgRenderer>
#include <QPainter>
#include <QFile>
#include <QColor>

class IconImageProvider : public QQuickImageProvider
{
public:
    IconImageProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}

    // id format: "<name>/<color hex without #>", e.g. "battery-full/fafafa"
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override
    {
        const QString name = id.section('/', 0, 0);
        const QColor color(QLatin1Char('#') + id.section('/', 1, 1));
        const QImage image = render(name, color.isValid() ? color : QColor(Qt::black), requestedSize);
        if (size)
            *size = image.size();
        return image;
    }

    // Also used for the tray icon
    static QImage render(const QString &name, const QColor &color, QSize requestedSize)
    {
        QByteArray svg = load(name);
        if (svg.isEmpty())
            return QImage();
        svg.replace("currentColor", color.name(QColor::HexRgb).toLatin1());

        QSvgRenderer renderer(svg);
        const QSizeF defaultSize = renderer.defaultSize();
        if (requestedSize.width() <= 0 && requestedSize.height() <= 0)
            requestedSize = defaultSize.toSize();
        else if (requestedSize.width() <= 0)
            requestedSize.setWidth(qRound(requestedSize.height() * defaultSize.width() / defaultSize.height()));
        else if (requestedSize.height() <= 0)
            requestedSize.setHeight(qRound(requestedSize.width() * defaultSize.height() / defaultSize.width()));

        QImage image(requestedSize, QImage::Format_ARGB32_Premultiplied);
        image.fill(Qt::transparent);
        QPainter painter(&image);
        painter.setRenderHint(QPainter::Antialiasing);
        painter.setOpacity(color.alphaF());
        renderer.render(&painter);
        return image;
    }

private:
    static QByteArray load(const QString &name)
    {
        for (const char *dir : {"icons", "illustrations"}) {
            QFile file(QStringLiteral(":/icons/assets/%1/%2.svg").arg(QLatin1String(dir), name));
            if (file.open(QIODevice::ReadOnly))
                return file.readAll();
        }
        return QByteArray();
    }
};
