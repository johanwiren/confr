# Confr

Your configurations, in one place.

## Status

Early stage, things will likely change.

## Installation

Confr is built using Babashka so the first step is to install [babashka](https://github.com/babashka/babashka#installation)

Then copy *bin/confr* to somewhere within your `$PATH`

## Usage

The first step in using Confr is to create a directory for your configurations

```bash
$ mkdir config
$ cd config
```

### Schema

The next step is to create a schema for your configuration.
A schema in Confr is a pure Malli schema and a minimal example would look like this:

```clojure
[:map
 {:registry {}}
 [:admin-password :string]
 [:server-port [:int {:min 1024 :max 65535}]]]
```

### First environment

Using that schema we can now generate our first environment, let's call it `dev` in *config/environments/dev.edn*:

```bash
$ mkdir environments
$ confr generate > environments/dev.edn
```

You should now have a `conf-dir` that looks like this:

```bash
$ tree -v
.
├── environments
│   └── dev.edn
└── schema.edn
```

And an *environments/dev* that looks something like this:

```clojure
{:admin-password "DJuh"
 :server-port 1033}
```

That's cool and all but not all environments are used to reading these `edn` files. So lets `export` an environment to json:

```bash
$ confr export dev --format json
{
  "admin-password" : "3U3",
  "server-port" : 1033
}
```

Or if you wish to have it as environment variables for use in a shell script:

```bash
$ confr export dev --format env-var
export ADMIN_PASSWORD="3U3"
export SERVER_PORT=1033
```

As a final step we can validate that our `dev` environment is actually correct according to the schema we defined:

```bash
$ confr validate dev
Environment dev is valid
```

### The second environment

So our app was a stunning success when running locally so we need to ship it to production. Let's call that environment `prod` and create it like this:

*environments/prod.edn*:

```clojure
{:admin-pasword "AR3allyStr0ngPasswoid,no_really"
 :server-port 1033}
```

And see if we can export it:

```bash
$ confr export prod
Invalid environment
{:admin-password ["missing required key"], :admin-pasword ["disallowed key"]}
```

What now? Oh, it seems we made a spelling mistake. It's `admin-password` not `admin-pasword`. Glad we didn't let that slip all the way through the deployment pipeline.

Let's fix it:

```clojure
{:admin-password "AR3allyStr0ngPasswoid,no_really"
 :server-port 1033}
```

```bash
$ confr validate prod
Environment prod is valid
```

Cool stuff, but we're repeating the server-port now, is that really necessary?

### Inclusion

We can extract all those common config values into a *environments/common.edn* file:

```clojure
{:server-port 1033}
```

And `include` that configuration file in our `prod` and `dev` environments:

```clojure
{:confr/include ["common"]
 :admin-pasword "AR3allyStr0ngPasswoid,no_really"}
```

Cool, but for prod I really don't want my password stored in a plain text file like that.

### Resolvers

Any value in the configuration can be replaced by a `confr/resolver`, examples:

```clojure
{:confr/resolver :file/plain
 :file "included.txt"}
```

This reads the file *included.txt* and replaces the value with the contents of the file.

```clojure
{:confr/resolver :file/json
 :json-file "included.json"}
```

This reads the file *included.json*, and replaces the value with the parsed json value in the file.

```clojure
{:confr/resolver :aws.secretsmanager/secret-string
 :secret-id "keys-to-the-castle"}
```

Now this one looks interesting. It will read a `secret-string` from AWS secrets manager and replace the value with the contents of the secret. Exactly what we need for our prod environment.

Let's try it out in *environments/prod.edn*:

```clojure
{:confr/include ["common"]
 :admin-password {:confr/resolver :aws.secretsmanager/secret-string
                  :secret-id "keys-to-the-castle"}}
```

```bash
$ confr validate prod
Environment prod is valid
```

So what happened here? According to the schema the `admin-password` should be a string, not this fancy map.

When validating an environment Confr will happily try to resolve all the resolveable values before validating so Confr accesses AWS secrets manager, downloads the secret and includes that value in the configuration. This is good for several reasons:

* We know that we are referring to the correct `secret-id`.
* We know that the value conforms to the schema, which in this case is just a string, but could be something more strict, like a url

Let's export it and see how it looks after we resolve it (assumes you have access to AWS and actually have a secret, if not you can try out the file resolvers above, the principle is the same):

```bash
$ confr export prod
{:admin-password "godSaveTheQu33n", :server-port 1033}
```

### Diffing

Once you have a more complex setup with some layers of inclusion it might be difficult to know how your environments differ from eachother. No sweat though, we can easily diff them:

```bash
$ confr diff prod dev
{:admin-password -"godSaveTheQu33n" +"3U3", :server-port 1033}
```

Or if you'd rather see it as JSON:

```bash
$ confr diff prod dev --format json
{
  "server-port" : 1033,
  "admin-password" : {
    "-" : "godSaveTheQu33n",
    "+" : "3U3"
  }
}
```

Now this is really cool, but about this export facility, sure we can pipe that to a file and use when staring up, but my boss says I cannot store any passord on disk. And I cannot use environment variables since they are somewhat "unsafe"..

### Serving a configuration

Confr can run an http server that serves the configuration one time and then exits so that you can launch your application and read the configuration during startup with no resolved values persisted to disk or in environment variables.

```bash
$ confr serve prod --format json --port 8081
```

Then in another terminal widow:

```bash
$ curl localhost:8081
{
  "server-port" : 1033,
  "admin-password" : "godSaveTheQu33n"
}
```

### Reusable schemas

Say that we want to add `admin-port` to our configuration, then we wouldn't want to write all that `min/max` stuff again. No sweat, this can easily be fixed by moving things into the `registry`:

```clojure
[:map
 {:registry {:ip/port [:int {:min 1024 :max 65535}]}}
 [:admin-password :string]
 [:server-port :ip/port]
 [:admin-port :ip/port]]
```
