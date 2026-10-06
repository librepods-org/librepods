{
  description = "AirPods liberated from Apple's ecosystem";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    crane.url = "github:ipetkov/crane";
    flake-parts.url = "github:hercules-ci/flake-parts";
    flake-compat.url = "https://flakehub.com/f/edolstra/flake-compat/1.tar.gz";
    systems.url = "github:nix-systems/default";
    treefmt-nix.url = "github:numtide/treefmt-nix";
  };

  outputs =
    inputs@{
      self,
      crane,
      flake-parts,
      systems,
      ...
    }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = import systems;
      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      perSystem =
        {
          self',
          pkgs,
          lib,
          ...
        }:
        let
          buildInputs =
            with pkgs;
            [
              dbus
              libpulseaudio
              alsa-lib
              bluez

              # https://github.com/max-privatevoid/iced/blob/master/DEPENDENCIES.md
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
              vulkan-loader
            ]
            ++ pkgs.lib.optionals pkgs.stdenv.isDarwin [
              pkgs.libiconv
            ];

          nativeBuildInputs = with pkgs; [
            pkg-config
            makeWrapper
          ];

          craneLib = crane.mkLib pkgs;
          unfilteredRoot = ./linux-rust/.;
          src = lib.fileset.toSource {
            root = unfilteredRoot;
            fileset = lib.fileset.unions [
              # Default files from crane (Rust and cargo files)
              (craneLib.fileset.commonCargoSources unfilteredRoot)
              (lib.fileset.maybeMissing ./linux-rust/assets/font)
            ];
          };

          commonArgs = {
            inherit buildInputs nativeBuildInputs src;
            strictDeps = true;

            # RUST_BACKTRACE = "1";
          };

          librepods = craneLib.buildPackage (
            commonArgs
            // {
              cargoArtifacts = craneLib.buildDepsOnly commonArgs;

              doCheck = false;

              # Wrap the binary after build to set runtime library path
              postInstall = ''
                wrapProgram $out/bin/librepods \
                  --prefix LD_LIBRARY_PATH : ${lib.makeLibraryPath buildInputs}
              '';

              meta = {
                description = "AirPods liberated from Apple's ecosystem";
                homepage = "https://github.com/kavishdevar/librepods";
                license = pkgs.lib.licenses.gpl3Only;
                maintainers = [ "kavishdevar" ];
                platforms = pkgs.lib.platforms.unix;
                mainProgram = "librepods";
              };
            }
          );
        in
        {
          checks = {
            inherit librepods;
          };

          packages.default = librepods;
          apps.default = {
            type = "app";
            program = lib.getExe librepods;
          };

          devShells.default = craneLib.devShell {
            name = "librepods-dev";
            checks = self'.checks;

            # NOTE: cargo and rustc are provided by default.
            buildInputs =
              with pkgs;
              [
                rust-analyzer
              ]
              ++ buildInputs;

            LD_LIBRARY_PATH = lib.makeLibraryPath buildInputs;
          };

          treefmt = {
            programs.nixfmt.enable = pkgs.lib.meta.availableOn pkgs.stdenv.buildPlatform pkgs.nixfmt-rfc-style.compiler;
            programs.nixfmt.package = pkgs.nixfmt-rfc-style;
          };
        };
    };
}
