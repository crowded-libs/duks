
<p align="center">
  <img src="duks-logo.png" alt="Duks Logo" width="200" />
</p>

# Duks - Kotlin Compose State Management and Control Flow

Duks is a lightweight, type-safe state management library for Kotlin Multiplatform applications, inspired by Redux. It provides a predictable, unidirectional data flow pattern with built-in support for middleware and Compose UI integration.

[![Build](https://github.com/crowded-libs/duks/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-v1.11.1-blue)](https://github.com/JetBrains/compose-multiplatform)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks%22)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Type-safe** state management with Redux-like architecture
- **Kotlin Multiplatform** — Android, iOS (device + simulator), JVM, and WebAssembly (wasmJs)
- **Built-in async** support with customizable lifecycle actions
- **Saga pattern** for complex workflow orchestration
- **Flexible persistence** with multiple strategies
- **Composable middleware** for extensibility
- **Compose integration** via `mapToPropsAsState` (equality-filtered slices)

Related libraries in the ecosystem (separate artifacts): `duks-routing`, `duks-storage-lmdb`, `duks-ga4`.

## Installation

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks:0.3.0")
}
```

## Quick Start

### 1. Define Your State

```kotlin
data class AppState(
    val counter: Int = 0,
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) : StateModel
```

### 2. Define Actions

```kotlin
sealed class AppAction : Action {
    data object Increment : AppAction()
    data object Decrement : AppAction()
    data class SetUser(val user: User) : AppAction()
    data class LoadUser(val id: String) : AppAction(), AsyncAction<User> {
        override suspend fun getResult(stateAccessor: StateAccessor): Result<User> =
            runCatching { userRepository.getUser(id) }
    }
}
```

### 3. Create a Reducer

Async middleware emits default lifecycle actions: `AsyncProcessing`, `AsyncResultAction`, `AsyncError`, and `AsyncComplete` (unless you override the `create*Action` methods).

```kotlin
val appReducer: Reducer<AppState> = { state, action ->
    when (action) {
        is AppAction.Increment -> state.copy(counter = state.counter + 1)
        is AppAction.Decrement -> state.copy(counter = state.counter - 1)
        is AppAction.SetUser -> state.copy(user = action.user)
        is AsyncProcessing -> state.copy(isLoading = true, error = null)
        is AsyncResultAction<*> -> when (action.initiatedBy) {
            is AppAction.LoadUser -> state.copy(
                user = action.result as User,
                isLoading = false
            )
            else -> state
        }
        is AsyncError -> state.copy(isLoading = false, error = action.error.message)
        is AsyncComplete -> state.copy(isLoading = false)
        else -> state
    }
}
```

### 4. Create the Store

Use `createStore` (the store constructor is internal):

```kotlin
val store = createStore(AppState()) {
    middleware {
        exceptionHandling(
            onError = { error, action -> /* metrics / logging hooks */ },
            errorAction = { error, action -> /* optional Action for the reducer */ null }
        )
        logging()
        async()
    }
    reduceWith(appReducer)
}

// Fire-and-forget (UI):
store.dispatch(AppAction.Increment)

// Await this action's middleware + reducer (not nested async/saga work):
// store.dispatchAsync(AppAction.LoadUser("id"))

// When the store is no longer needed (tests, multi-window, etc.):
// store.close()
```

**Concurrency:** only the reducer’s state write is serialized. Concurrent `dispatch` calls may interleave in middleware. Prefer `dispatchAsync` when you need to await completion of a single action’s chain.

### Recommended middleware order

Order is the order you register them (outermost first):

1. **exceptionHandling** — first, so failures in the chain are caught  
2. **logging** (optional)  
3. **caching** (optional)  
4. **persistence** — restore runs on store create; keep before heavy side effects  
5. **domain middleware** (routing, analytics, …)  
6. **sagas**  
7. **async** — often last so lifecycle actions re-enter the full chain  

### 5. Use in Compose

Prefer selecting a slice so unrelated state changes do not recompose the screen.  
`TProps` should be equality-friendly (e.g. data classes).

```kotlin
@Composable
fun CounterScreen(store: KStore<AppState>) {
    val counter by store.state.mapToPropsAsState { counter }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Count: $counter")
        Button(onClick = { store.dispatch(AppAction.Increment) }) {
            Text("Increment")
        }
    }
}
```

For non-Compose collectors of a distinct slice:

```kotlin
store.state.mapToProps { user }.collect { user -> /* ... */ }
```

### Complete Compose Example

```kotlin
data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false
) : StateModel

data class TodoItem(val id: String, val text: String, val completed: Boolean = false)

data class UpdateInputText(val text: String) : Action
data class AddTodo(val text: String) : Action
data class ToggleTodo(val id: String) : Action
data class DeleteTodo(val id: String) : Action

class LoadTodos : AsyncAction<List<TodoItem>> {
    override suspend fun getResult(stateAccessor: StateAccessor): Result<List<TodoItem>> =
        runCatching { todoRepository.getAllTodos() }
}

val todoReducer: Reducer<TodoState> = { state, action ->
    when (action) {
        is UpdateInputText -> state.copy(inputText = action.text)
        is AddTodo -> state.copy(
            items = state.items + TodoItem(Uuid.random().toString(), action.text),
            inputText = ""
        )
        is ToggleTodo -> state.copy(
            items = state.items.map {
                if (it.id == action.id) it.copy(completed = !it.completed) else it
            }
        )
        is DeleteTodo -> state.copy(items = state.items.filterNot { it.id == action.id })
        is AsyncProcessing ->
            if (action.initiatedBy is LoadTodos) state.copy(isLoading = true) else state
        is AsyncResultAction<*> ->
            if (action.initiatedBy is LoadTodos) {
                @Suppress("UNCHECKED_CAST")
                state.copy(items = action.result as List<TodoItem>, isLoading = false)
            } else state
        is AsyncError, is AsyncComplete ->
            if ((action as AsyncInitiatedByAction).initiatedBy is LoadTodos) {
                state.copy(isLoading = false)
            } else state
        else -> state
    }
}

@Composable
fun TodoApp() {
    val store = remember {
        createStore(TodoState()) {
            middleware {
                exceptionHandling()
                logging()
                async()
            }
            reduceWith(todoReducer)
        }
    }

    LaunchedEffect(Unit) {
        store.dispatch(LoadTodos())
    }

    TodoScreen(store)
}

data class TodoScreenProps(
    val items: List<TodoItem>,
    val inputText: String,
    val isLoading: Boolean
)

@Composable
fun TodoScreen(store: KStore<TodoState>) {
    val props by store.state.mapToPropsAsState {
        TodoScreenProps(items, inputText, isLoading)
    }
    // Build UI from props; unrelated AppState changes will not recompose this screen.
}
```

## Advanced Features

### Sagas

Sagas orchestrate multi-step workflows with their own state:

```kotlin
data class OnboardingSagaState(
    val userId: String,
    val profileComplete: Boolean = false,
    val tutorialComplete: Boolean = false,
    val currentStep: String = "started"
)

data class UserSignedUp(val userId: String, val email: String) : Action
data class ProfileCompleted(val userId: String) : Action
data class TutorialFinished(val userId: String) : Action
data class OnboardingCompleted(val userId: String) : Action

class OnboardingSaga : SagaDefinition<OnboardingSagaState> {
    override val name = "onboarding"

    override fun configure(saga: SagaConfiguration<OnboardingSagaState>) {
        saga.startsOn<UserSignedUp> { action ->
            SagaTransition.Continue(
                OnboardingSagaState(
                    userId = action.userId,
                    currentStep = "profile_setup"
                ),
                effects = listOf(
                    SagaEffect.Dispatch(ShowProfileSetupScreen(action.userId))
                )
            )
        }

        saga.on<ProfileCompleted>(
            condition = { action, state -> action.userId == state.userId }
        ) { _, state ->
            val newState = state.copy(profileComplete = true, currentStep = "tutorial")
            if (state.tutorialComplete) {
                SagaTransition.Complete(
                    effects = listOf(SagaEffect.Dispatch(OnboardingCompleted(state.userId)))
                )
            } else {
                SagaTransition.Continue(
                    newState,
                    effects = listOf(SagaEffect.Dispatch(ShowTutorialScreen(state.userId)))
                )
            }
        }

        saga.on<TutorialFinished>(
            condition = { action, state -> action.userId == state.userId }
        ) { _, state ->
            val newState = state.copy(tutorialComplete = true, currentStep = "completed")
            if (state.profileComplete) {
                SagaTransition.Complete(
                    effects = listOf(SagaEffect.Dispatch(OnboardingCompleted(state.userId)))
                )
            } else {
                SagaTransition.Continue(
                    newState,
                    effects = listOf(SagaEffect.Dispatch(ShowProfileSetupScreen(state.userId)))
                )
            }
        }
    }
}

val store = createStore(AppState()) {
    middleware {
        exceptionHandling()
        sagas {
            register(OnboardingSaga())
            saga("payment") {
                startsOn<InitiatePayment> { action ->
                    SagaTransition.Continue(
                        PaymentSagaState(orderId = action.orderId),
                        effects = listOf(
                            SagaEffect.Dispatch(ProcessPayment(action.orderId))
                        )
                    )
                }
            }
        }
        async()
    }
    reduceWith(appReducer)
}
```

### Custom Async Actions

Override the lifecycle factories for domain-specific loading/error actions:

```kotlin
interface AccountAsyncAction<T : Any> : AsyncAction<T> {
    override fun createProcessingAction(): Action = AccountLoading
    override fun createErrorAction(error: Throwable): Action = AccountError(error)
    override fun createCompleteAction(): Action = AccountComplete
}

data class SignIn(val email: String, val password: String) :
    Action, AccountAsyncAction<Account> {
    override suspend fun getResult(stateAccessor: StateAccessor): Result<Account> =
        accountService.signIn(email, password)

    override fun createResultAction(result: Account): Action = AccountSignedIn(result)
}
```

### Persistence

```kotlin
class FileStateStorage : StateStorage<AppState> {
    override suspend fun save(state: AppState) {
        File("app_state.json").writeText(Json.encodeToString(state))
    }

    override suspend fun load(): AppState? = try {
        Json.decodeFromString(File("app_state.json").readText())
    } catch (_: Exception) {
        null
    }

    override suspend fun clear() {
        File("app_state.json").delete()
    }

    override suspend fun exists(): Boolean = File("app_state.json").exists()
}

val store = createStore(AppState()) {
    middleware {
        exceptionHandling()
        persistence(
            storage = FileStateStorage(),
            strategy = PersistenceStrategy.Debounced(500) // milliseconds
        )
        async()
    }
    reduceWith(appReducer)
}
```

Strategies: `OnEveryChange`, `Debounced(delayMs)`, `OnAction(setOf(...))`, `Conditional { ... }`, `Combined(...)`.

Saga instances can be persisted via `sagas(storage = ..., persistenceStrategy = ...)` on the middleware builder.

### Action Caching

`CacheableAction` uses `cacheKey` (default: `toString()`) and `expiresAfter` for TTL.
`MapActionCache` removes expired entries on read and can cap size via `MapActionCache(maxSize = …)`.

```kotlin
data class SearchProducts(val query: String) : Action, CacheableAction {
    override val cacheKey: String = "search:$query"
    override val expiresAfter: Instant =
        Clock.System.now().plus(5, DateTimeUnit.MINUTE, TimeZone.currentSystemDefault())
}

val store = createStore(AppState()) {
    middleware {
        exceptionHandling()
        caching(MapActionCache(maxSize = 256))
        async()
    }
    reduceWith(appReducer)
}
```

Caching is best for pure sync transforms of the same action; it is not a substitute for memoizing async network results.

## Best Practices

1. **State**: Keep state immutable and normalized  
2. **Actions**: Prefer sealed hierarchies; implement `AsyncAction` for IO  
3. **Compose**: Use `mapToPropsAsState` with data-class props  
4. **Persistence**: Debounce frequent UI state; use `OnAction` for critical checkpoints  
5. **Lifecycle**: Call `store.close()` when disposing a store (tests, secondary windows)  
6. **Middleware**: Put exception handling first; put async after sagas/domain middleware when lifecycle actions should re-enter the chain  

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.
