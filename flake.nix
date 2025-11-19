{
  description = "AirPods liberated from Apple's ecosystem";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";
    flake-parts.url = "github:hercules-ci/flake-parts";
    flake-compat.url = "https://flakehub.com/f/edolstra/flake-compat/1.tar.gz";
    treefmt-nix.url = "github:numtide/treefmt-nix";
  };

  outputs =
    inputs@{ flake-parts, systems, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import systems;
      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      perSystem =
        {
          pkgs,
          ...
        }:
        let
          # Build time
          nativeBuildInputs = with pkgs; [
            cmake
            pkg-config
            qt6.wrapQtAppsHook
            makeWrapper
          ];

          # Run time
          buildInputs = with pkgs; [
            qt6.qtbase
            qt6.qtconnectivity
            qt6.qtmultimedia
            qt6.wrapQtAppsHook

            openssl
            bluez
            libpulseaudio
          ];

          librepods = pkgs.stdenv.mkDerivation {
            pname = "librepods";
            version = "dev";

            src = ./linux;

            inherit nativeBuildInputs;
            inherit buildInputs;

            meta = {
              description = "AirPods liberated from Apple's ecosystem";
              homepage = "https://github.com/kavishdevar/librepods";
              license = pkgs.lib.licenses.gpl3Only;
              maintainers = [ "kavishdevar" ];
              platforms = pkgs.lib.platforms.unix;
              mainProgram = "librepods";
            };
          };
        in
        {
          packages = {
            default = librepods;
            inherit librepods;
          };

          devShells.default = pkgs.mkShell {
            name = "librepods-dev";

            buildInputs =
              with pkgs;
              [
                gdb
              ]
              ++ buildInputs;
          };

          treefmt = {
            programs.nixfmt.enable = pkgs.lib.meta.availableOn pkgs.stdenv.buildPlatform pkgs.nixfmt-rfc-style.compiler;
            programs.nixfmt.package = pkgs.nixfmt-rfc-style;
          };
        };
    };
}
