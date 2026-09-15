# Superwall purchase delegate example

This source example compiles against Superwall Android **2.8.3**. It is a
non-published verification module, outside the Nuxie SDK artifact and base
example app. Copy its delegate into an existing Superwall host, or depend on
the example project locally during development.

Configure Superwall at the application level with its automatic purchase
controller, identify the same customer in both SDKs, and configure Nuxie before
its first setup:

```kotlin
val configuration = NuxieConfiguration(nuxiePublicKey).apply {
  purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
  purchaseDelegate = NuxieSuperwallPurchaseDelegate(expectedCustomerId = customerId)
}
Nuxie.setup(application, configuration)
Nuxie.identify(customerId)
```

Nuxie types are in `ai.nuxie.sdk`, purchase types in `ai.nuxie.sdk.billing`,
and this delegate in `ai.nuxie.example.superwall`. Keep identities synchronized
on login/logout and prevent account changes during checkout. Do not configure
Superwall with a custom purchase controller that routes back into Nuxie; that
would recurse. Keep a resumed host Activity and prevent overlapping Superwall
and Nuxie presentations. Neither SDK should be reinitialized on Activity
recreation.

An authored Journey's Purchase Product step invokes this delegate after a
trigger. The adapter constructs a Superwall StoreProduct from Nuxie's retained
native ProductDetails with explicit base-plan/purchase-option and offer
selection. It checks Superwall's selected identity and token before calling
direct purchase; it does not register another Superwall paywall. Missing or
different terms fail instead of falling back. Superwall owns Play completion;
the example does not acknowledge or consume through another BillingClient.

Purchased, cancelled, pending and failed results remain distinct; coroutine
cancellation is rethrown. Restore requires a successful restore plus nonempty
active Superwall entitlements before returning Restored. Keep using provider
access until the Connector is configured, mapped, synchronized and explicitly
cut over to Nuxie Feature authority. Provider checkout success alone grants no
Nuxie Feature.

The runnable host in `example-app` passes `expectedCustomerId` to the delegate.
That rejects checkout or restore while Superwall's asynchronous identity state
still exposes a different customer. Use that guard when copying the example;
it does not make an in-flight account switch safe.

## Limits and qualification

Superwall 2.8.3's public direct-purchase API and
[AutomaticPurchaseController](https://github.com/superwall/Superwall-Android/blob/2.8.3/superwall/src/main/java/com/superwall/sdk/store/AutomaticPurchaseController.kt)
provide no personalized-price input. This adapter therefore rejects
`isOfferPersonalized = true` before invoking provider checkout. Supporting those
terms needs a provider capability or another reviewed purchase-ownership path;
silently clearing the flag is not supported.

The pinned [RawStoreProduct](https://github.com/superwall/Superwall-Android/blob/2.8.3/superwall/src/main/java/com/superwall/sdk/store/abstractions/product/RawStoreProduct.kt)
supports explicit subscription and one-time option selection, with fallback
behavior that this adapter checks and rejects when it differs from Nuxie's
resolved terms. This source supports ordinary non-personalized purchases;
upgrades/downgrades with explicit replacement policies, actual Google Play
checkout, pending recovery/process death, acknowledgement/consumption, Activity
and account changes during provider checkout remain unqualified. Do not use
this sample as a subscription-replacement integration. Test Store bypasses
purchase delegates and is not provider qualification.

The module uses the local SDK project and staged runtime candidate from the
root README; consumption of a published SDK artifact remains pending.

```bash
NUXIE_RUNTIME_USE_LOCAL=1 ./gradlew :example-superwall:test :example-superwall:lint verifyProviderBoundary
```

The SDK lint task runs these example checks too. Tests use pinned Superwall
models to verify selected terms and result mappings; they do not perform a
store transaction. The dependency check rejects Superwall/RevenueCat in the
SDK's debug and release runtime dependency graphs.
