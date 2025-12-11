
{
  description = "A Nix-flake-based Rust development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs =
    { self, nixpkgs, ... }@inputs:

    let
      supportedSystems = [
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
      pkgsFor = system: import nixpkgs {
        inherit system;
      };
    in
    {
      packages = forAllSystems (system: let
      pkgs = pkgsFor system;
      in {
      default = pkgs.rustPlatform.buildRustPackage rec {
        name = "librepods";
        version = "0.1.0";

        doCheck = false;

        nativeBuildInputs = with pkgs; [
          pkg-config
          libpulseaudio
          autoPatchelfHook
          makeWrapper
        ];

        buildInputs = with pkgs; [
          dbus
          libpulseaudio
          wayland

          # From https://github.com/max-privatevoid/iced/blob/master/DEPENDENCIES.md
          expat
          fontconfig
          freetype
          freetype.dev
          libGL
          pkg-config
          xorg.libX11
          xorg.libXcursor
          xorg.libXi
          xorg.libXrandr
          wayland
          libxkbcommon
        ];

        src = ./linux-rust;
        cargoHash = "sha256-Ebqx+UU2tdygvqvDGjBSxbkmPnkR47/yL3sCVWo54CU=";

        postFixup = ''
          wrapProgram $out/bin/librepods --suffix LD_LIBRARY_PATH : ${pkgs.lib.makeLibraryPath buildInputs}
        '';
      };
    });
  };
}
