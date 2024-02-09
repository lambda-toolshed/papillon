# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- The auto-naming of unnamed interceptors has changed to use the hash of the interceptor instead of its ordinal position in the initial queue.  Position is not easily defined for interceptors queued after the initial execution.
### Added
- The Chrysalis protocol is used by papillon to realize deferred contexts between interceptor transitions.
- The execution of the interceptor chain can now be run asynchronously or synchronously.
- If a var is queued (instead of an actual map interceptor), it will be dereferenced before being queued.

[Unreleased]: https://github.com/lambda-toolshed/papillon/compare/0.1.1...HEAD
[0.1.1]: https://github.com/lambda-toolshed/papillon/compare/0.1.0...0.1.1
