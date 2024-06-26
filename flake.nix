{
  description = "Snooper Event Notifier";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    milquetoast = {
      url = "github:fudoniten/milquetoast";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure = {
      url = "github:fudoniten/fudo-clojure";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, milquetoast, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        fudoClojureLib = fudo-clojure.packages."${system}".fudo-clojure;
        milquetoastLib = milquetoast.packages."${system}".milquetoast;
        cljLibs = {
          "org.fudo/fudo-clojure" = fudoClojureLib;
          "org.fudo/milquetoast" = milquetoastLib;
        };
      in {
        packages = rec {
          default = snooper-server;
          snooper-server = helpers.packages."${system}".mkClojureBin {
            name = "org.fudo/snooper-server";
            primaryNamespace = "snooper.cli";
            src = ./.;
            inherit cljLibs;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps cljLibs) ];
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
