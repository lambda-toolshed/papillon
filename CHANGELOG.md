# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- The auto-naming of unnamed interceptors has changed to use the hash of the interceptor instead of its ordinal position in the initial queue.  Position is not easily defined for interceptors queued after the initial execution.
- Transitions from the leave stage to the error stage will first consider the offending interceptor's `:error` stage  before consuming the stack.
- The state of the stack used by papillon when working back out of the chain is now only updated when popping off the top of the stack to execute the `:final` stage.
### Added
- The Chrysalis protocol is used by papillon to realize deferred contexts between interceptor transitions.
- The execution of the interceptor chain can now be run asynchronously or synchronously.
- If a var is queued (instead of an actual map interceptor), it will be dereferenced before being queued.
- The optional `:final` stage has been added.

[Unreleased]: https://github.com/lambda-toolshed/papillon/compare/0.1.1...HEAD
[0.1.1]: https://github.com/lambda-toolshed/papillon/compare/0.1.0...0.1.1
