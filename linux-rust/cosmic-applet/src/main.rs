mod applet;
mod ipc;

fn main() -> cosmic::iced::Result {
    cosmic::applet::run::<applet::Applet>(())
}
