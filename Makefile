# Some inspiration: https://github.com/git/git/blob/master/Makefile
# More inspiration: https://clarkgrubb.com/makefile-style-guide
SHELL = /bin/bash

src-clj = $(shell find src/ -type f -name '*.clj' -or -name '*.cljc' -or -name '*.edn')
src-cljs = $(shell find src/ -type f -name '*.cljs' -or -name '*.cljc' -or -name '*.clj' -or -name '*.edn')
srcfiles = $(src-clj) $(src-cljs)

test-clj = $(shell find test/ -type f -name '*.clj' -or -name '*.cljc' -or -name '*.edn')
test-cljs = $(shell find test/ -type f -name '*.cljs' -or -name '*.cljc' -or -name '*.clj' -or -name '*.edn')
testfiles = $(test-clj) $(test-cljs)

target = ./target

# This is the default target because it is the first real target in this Makefile
.PHONY: default # Same as "make ci"
default: ci

.PHONY: assert-clean # Fail if the git repo is dirty (untracked files, modified files, or files are in the index)
assert-clean:
ifeq ($(DRO),true)
	@echo "Skipping dirty repo check"
else
	@test -z "$$(git status --porcelain)"
endif

.PHONY: ci
ci: assert-clean
	clojure -T:build ci

.PHONY: test # Run the Clojure and ClojureScript test suites
test: test-clj test-cljs

.PHONY: test-clj
test-clj: .make.test-clj

.PHONY: test-cljs
test-cljs: .make.test-cljs

.make.test-clj: deps.edn $(testfiles) $(srcfiles)
	clojure -T:build test-clj
	touch .make.test-clj

.make.test-cljs: deps.edn $(testfiles) $(srcfiles)
	clojure -T:build test-cljs
	touch .make.test-cljs

lint: $(testfiles) $(srcfiles)
	clojure -T:build lint

format: $(testfiles) $(srcfiles)
	clojure -T:build ensure-format

$(target)/:
	mkdir -p $@

install: ci
	clojure -T:build install

deploy: ci
	clojure -T:build deploy

# clean:
# 	rm -f $(jar-file) $(pom-file)
# 	rm -rf target/*
# 	rm -rf cljs-test-runner-out
# 	rm -f .make.*

# Copied from: https://github.com/jeffsp/makefile_help/blob/master/Makefile
# Tab nonesense resolved with help from StackOverflow... need a literal instead of the \t escape on MacOS
help: # Generate list of targets with descriptions
	@grep '^.PHONY: .* #' Makefile | sed 's/\.PHONY: \(.*\) # \(.*\)/\1	\2/' | expand -t20
