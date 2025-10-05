{
  description = "Nixpkg for LibrePods";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = import nixpkgs {inherit system;};
    in {
      packages.default = pkgs.stdenv.mkDerivation {
        pname = "librepods";
        version = "0.1.0";

        src = ./linux;

        nativeBuildInputs = with pkgs; [
          cmake
          pkg-config
          qt6.wrapQtAppsHook
          makeWrapper
        ];

        buildInputs = with pkgs; [
          qt6.qtbase
          qt6.qtdeclarative
          qt6.qtsvg
          qt6.qtconnectivity
          qt6.qtmultimedia
          qt6Packages.qtstyleplugin-kvantum

          openssl
          bluez
          libpulseaudio
        ];

        postInstall = ''
          wrapProgram $out/bin/librepods \
            --unset QT_STYLE_OVERRIDE
        '';

        meta = with pkgs.lib; {
          description = "A cross-platform app to manage AirPods on Linux and Android.";
          homepage = "https://github.com/kavishdevar/librepods";
          license = licenses.agpl3Only;
          maintainers = [];
          platforms = platforms.linux;
        };
      };

      apps.default = flake-utils.lib.mkApp {
        drv = self.packages.${system}.default;
      };

      devShells.default = pkgs.mkShell {
        inputsFrom = [self.packages.${system}.default];
      };
    });
}
