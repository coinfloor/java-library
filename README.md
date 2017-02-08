java-library
============

## Coinfloor's Java client library

Coinfloor's application programming interface (API) provides our clients programmatic access to control aspects of their accounts and to place orders on the Coinfloor trading platform. The Java client library exposes the Coinfloor API to your Java application.

### Invocation models

The library presents three invocation models for each API method:

* **Synchronous.** The method call returns the result of the method (or throws an exception). This is the easiest model to use, but it can result in poor performance, as it does not allow for pipelining of requests.

* **Asynchronous (Polled).** The method call returns a `java.util.concurrent.Future` object that can be polled or waited upon for the result of the method. This model is moderately easy to use and allows for pipelining of requests. See [Example.java][] for an example of using this invocation model.

* **Asynchronous (Callback).** The method call accepts a `Callback` object that will be notified when the result of the method is available. The callback must complete its work quickly (without blocking or waiting). This model is the most difficult to use but offers the most flexibility.

[Example.java]: https://github.com/coinfloor/java-library/blob/master/src/test/java/uk/co/coinfloor/api/Example.java

### Third-party library dependencies

* [Legion of the Bouncy Castle Java cryptography APIs](http://www.bouncycastle.org/java.html)


## API

For an explanation of our API methods and what they return, please see our [API specification](https://github.com/coinfloor/API).

### Numbers and scale

All quantities and prices are transmitted and received as integers with implicit scale factors. For scale information, please see [SCALE.md](https://github.com/coinfloor/API/blob/master/SCALE.md).


## Licence

Copyright 2014 Coinfloor LTD.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

> http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


## Give us your feedback!

We're always looking to get as much feedback as we can. We want to hear your opinion. [Contact us](http://support.coinfloor.co.uk/).
