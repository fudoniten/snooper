{
  description = "Snooper Event Notifier";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://fudo.dev/public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, ... }:
    utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages."${system}";
      in {
        packages = rec {
          default = snooper-server;
          snooper-server = helpers.packages."${system}".mkClojureBin {
            name = "org.fudo/snooper-server";
            primaryNamespace = "snooper.cli";
            src = ./.;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps { }) ];
          };
          snooperServer = pkgs.mkShell {
            buildInputs = with self.packages."${system}"; [ snooper-server ];
          };
        };
      }) // {
        nixosModules = rec {
          default = snooper-server;
          snooper-server = import ./module.nix self.packages;
        };
      };
}
