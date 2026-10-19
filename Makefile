# Syx Multiplayer — convenience targets over the scripts in syx-multiplayer/.
# Configure machine-specific paths in syx-multiplayer/local.env (see
# local.env.example). You need your own copy of Songs of Syx to build.

MOD := syx-multiplayer

.PHONY: help setup build test package release release-dry install-deps

help:
	@echo "make setup        download ASM (+ a Temurin 21 JDK if none is found) into tools/"
	@echo "make build        compile the mod and install it into your mods folder(s)"
	@echo "make test         build, run the headless test suite, install"
	@echo "make package      build and zip into $(MOD)/dist/"
	@echo "make release      cut the next patch release from main (tests, build, tag, GitHub release)"
	@echo "                  VERSION=0.4.0 make release / BUMP=minor make release / BUMP=major make release"
	@echo "make release-dry  everything release does except tagging and publishing"

setup:
	@if [ -x tools/jdk21/bin/javac ] || [ -n "$$SYX_JDK" ] || [ -n "$$JAVA_HOME" ] || command -v javac >/dev/null; then \
		$(MOD)/setup-tools.sh; else $(MOD)/setup-tools.sh --jdk; fi

build:
	$(MOD)/build.sh

test:
	$(MOD)/test.sh

package:
	$(MOD)/package.sh

# BUMP=patch|minor|major (default patch) or VERSION=X.Y.Z
BUMP ?= patch
RELEASE_ARGS := $(if $(VERSION),$(VERSION),$(if $(filter-out patch,$(BUMP)),--$(BUMP),))

release:
	$(MOD)/release.sh $(RELEASE_ARGS)

release-dry:
	$(MOD)/release.sh $(RELEASE_ARGS) --dry-run
