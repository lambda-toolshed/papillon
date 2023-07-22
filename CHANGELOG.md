# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

### Added
- A new protocol `Chrysalis` as the unifying result type for synchronous and asynchronous results.
  - Add support for IDeref protocol as Chrysalis, allowing `Future` results to be synchronously awaited and turned back to synchronous execution flow.
- New exception entry under the `::error` key when the context is lost during execution.

### Changed
- Fully synchronous interceptor chain returns the result directly, or throws the value in the `::error` key.
- Interceptor chain with at least one asynchronous interceptor return value in it now:
  - returns the result wrapped in the asynchronous type of the outermost interceptor
  - the wrapped result is now either
    - the value under the `::error` key in the context, if present,
    - or the final context otherwise

### Removed
- Remove the direct dependency on `core.async`, a new namespace of `lambda-toolshed.papillon.async.core-async` has been included to support extending a `core.async` channel to be a `Chrysalis`.
  - This should allow usage of Papillon in other target runtimes, e.g. `nbb`, that do not support `core.async`

## 0.0.1-PREVIEW - 2022-01-20
### Added
- Initial Preview release

[Unreleased]: https://github.com/lambda-toolshed/papillon/compare/0.1.1...HEAD
[0.1.1]: https://github.com/lambda-toolshed/papillon/compare/0.1.0...0.1.1
