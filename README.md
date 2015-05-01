# lifo-set
[![Build Status](https://travis-ci.org/dk14/lifo-set.svg?branch=master)](https://travis-ci.org/dk14/lifo-set)
[![Coverage Status](https://coveralls.io/repos/dk14/lifo-set/badge.svg?branch=master)](https://coveralls.io/r/dk14/lifo-set?branch=master)

implementation of LIFO with unique elements.

Actually, here is several implementations:

- `AkkaBasedCache` uses actor to gurantee sequential access. This is the slower and most reliable one. Logically, same can be achieved with `@synchronized`. All other implementations here are CompareAndSwap based, so they are completely non-blocking.
- `LinearAccessAndConstantPut` uses `TrieMap` (works pretty same as `ConcurrentHashMap`) in combination with atomic vector clock. `putIfAbscent` is used for atomic put. That's pretty fast, but getting a head takes linear time, so it's not recommended for big collections.
- `ConstantOperations` is the fastest one for bigger data. All operations are effectively constant. It's based on `TrieMap` + `ConcurrentLinkedDeque`. Both are using `AtomicReference`s inside... no locks. The code is simple but synchronisation isn't trivial, so the more travis-ci builds was ran - the better. `Deque` itself may contain duplicating elements for a while (it's eventually consistent), but `Map` will always guarantee uniqueness and used as a primary source. So, this implementation guarantees sequential consistency (provides happens-before relationships) as usual. However, it may reorder puts between threads, which means that you may win your put to other thread (receive `true` from `add`), but actually another object with same `hashCode` may be returned from `peek`/`take()`. So, be careful with comparisons - use `==` instead of reference equality (`eq`). 

P.S. `T99.scala` - this is just T9 search tool. It's here because it's part of exercise.
