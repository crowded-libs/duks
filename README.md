
<p align="center">
  <img src="duks-logo.png" alt="Duks Logo" width="200" />
</p>

# Duks - Kotlin Compose State Management and Control Flow

Duks is a lightweight, type-safe state management library for Kotlin Multiplatform applications, inspired by Redux. It provides a predictable, unidirectional data flow pattern with built-in support for middleware and Compose UI integration.

[![Build](https://github.com/crowded-libs/duks/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.21-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-v1.8.0-blue)](https://github.com/JetBrains/compose-multiplatform)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks%22)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- 🎯 **Type-safe** state management with Redux-like architecture
- 🚀 **Kotlin Multiplatform** Works across Android, iOS, JVM, watchOS, tvOS, Linux, Windows, and WebAssembly targets
- ⚡ **Built-in async** support with customizable lifecycle actions
- 🔄 **Saga pattern** for complex workflow orchestration
- 💾 **Flexible persistence** with multiple strategies
- 🧩 **Composable middleware** for extensibility
- 🎨 **Compose integration** with optimized recomposition

## Installation

Add Duks to your project by including it in your Gradle build file:

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks:0.2.0")
}
```

## Quick Start

### 1. Define Your State

```kotlin
data class AppState(
    val counter: Int = 0,
    val user: User? = null,
    val isLoading: Boolean = false
) : StateModel
```

### 2. Define Actions

```kotlin
sealed class AppAction : Action {
    data object Increment : AppAction()
    data object Decrement : AppAction()
    data class SetUser(val user: User) : AppAction()
    data object LoadUser : AppAction(), AsyncAction<User>
}
```

### 3. Create a Reducer

```kotlin
val appReducer: Reducer<AppState> = { state, action ->
    when (action) {
        is AppAction.Increment -> state.copy(counter = state.counter + 1)
        is AppAction.Decrement -> state.copy(counter = state.counter - 1)
        is AppAction.SetUser -> state.copy(user = action.user)
        is AsyncAction.Processing -> state.copy(isLoading = true)
        is AsyncAction.Result -> when (action.initiatedBy) {
            is AppAction.LoadUser -> state.copy(user = action.data as User, isLoading = false)
            else -> state
        }
        else -> state
    }
}
```

### 4. Create the Store

```kotlin
val store = KStore(
    initialState = AppState(),
    reducer = appReducer,
    middleware = listOf(
        exceptionHandling(),
        logging(),
        async())
    )
)
```

### 5. Use in Compose

```kotlin
@Composable
fun CounterScreen(store: KStore<CounterState>) {
    // Access store state in a Compose-friendly way
    val state by store.state.collectAsState()
    
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Count: ${state.count}")

        Button(onClick = { store.dispatch(Increment()) }) {
            Text("Increment")
        }

        Button(onClick = { store.dispatch(Increment(5)) }) {
            Text("Increment by 5")
        }
    }
}
```

### Complete Compose Example

Here's a complete example showing a todo app with Duks and Compose:

```kotlin
// 1. Define the state
data class TodoState(
    val items: List<TodoItem> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false
) : StateModel

data class TodoItem(val id: String, val text: String, val completed: Boolean = false)

// 2. Define actions
data class UpdateInputText(val text: String) : Action
data class AddTodo(val text: String) : Action
data class ToggleTodo(val id: String) : Action
data class DeleteTodo(val id: String) : Action
data class LoadTodos : AsyncAction<List<TodoItem>> {
    override suspend fun execute(): Result<List<TodoItem>> {
        return try {
            // Simulate loading todos from a repository
            val todos = todoRepository.getAllTodos()
            Result.success(todos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// 3. Create the reducer
val todoReducer: Reducer<TodoState> = { state, action ->
    when (action) {
        is UpdateInputText -> state.copy(inputText = action.text)
        is AddTodo -> state.copy(
            items = state.items + TodoItem(UUID.randomUUID().toString(), action.text),
            inputText = "" // Clear input after adding
        )
        is ToggleTodo -> state.copy(
            items = state.items.map { 
                if (it.id == action.id) it.copy(completed = !it.completed) else it 
            }
        )
        is DeleteTodo -> state.copy(
            items = state.items.filterNot { it.id == action.id }
        )
        is AsyncInitiatedByAction -> {
            if (action.initiator is LoadTodos) {
                state.copy(isLoading = true)
            } else state
        }
        is AsyncSuccessAction<*, *> -> {
            if (action.initiator is LoadTodos && action.result is List<*>) {
                @Suppress("UNCHECKED_CAST")
                state.copy(
                    items = action.result as List<TodoItem>,
                    isLoading = false
                )
            } else state
        }
        else -> state
    }
}

// 4. Create the Compose UI
@Composable
fun TodoApp() {
    // Create the store
    val store = remember {
        createStore(TodoState()) {
            middleware {
                async()
                logging()
            }
            reduceWith(todoReducer)
        }
    }

    // Load todos when the screen first appears
    LaunchedEffect(Unit) {
        store.dispatch(LoadTodos())
    }

    TodoScreen(store)
}

@Composable
fun TodoScreen(store: KStore<TodoState>) {
    // Access the state from the store
    val state by store.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Input field and add button
        Row(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = state.inputText,
                onValueChange = { store.dispatch(UpdateInputText(it)) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a todo") }
            )

            Button(
                onClick = { 
                    if (state.inputText.isNotBlank()) {
                        store.dispatch(AddTodo(state.inputText))
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Add")
            }
        }

        // Loading indicator
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
            )
        }

        // Todo list
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            items(state.items) { todo ->
                TodoItem(
                    todo = todo,
                    onToggle = { store.dispatch(ToggleTodo(todo.id)) },
                    onDelete = { store.dispatch(DeleteTodo(todo.id)) }
                )
            }
        }
    }
}

@Composable
fun TodoItem(todo: TodoItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = todo.completed,
            onCheckedChange = { onToggle() }
        )

        Text(
            text = todo.text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
            color = if (todo.completed) Color.Gray else Color.Black
        )

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}
```

## Advanced Features

### Sagas

Sagas provide powerful workflow orchestration for complex async scenarios. Each saga maintains its own independent state throughout its lifecycle:

```kotlin
// Define saga-specific state
data class OnboardingSagaState(
    val userId: String,
    val profileComplete: Boolean = false,
    val tutorialComplete: Boolean = false,
    val currentStep: String = "started"
)

// Define actions that interact with the saga
data class UserSignedUp(val userId: String, val email: String) : Action
data class ProfileCompleted(val userId: String) : Action
data class TutorialFinished(val userId: String) : Action
data class OnboardingCompleted(val userId: String) : Action

// Create the onboarding saga
class OnboardingSaga : SagaDefinition<OnboardingSagaState> {
    override val name = "onboarding"
    
    override fun configure(saga: SagaConfiguration<OnboardingSagaState>) {
        // Start saga when user signs up
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
        
        // Handle profile completion
        saga.on<ProfileCompleted>(
            condition = { action, state -> action.userId == state.userId }
        ) { action, state ->
            val newState = state.copy(
                profileComplete = true,
                currentStep = "tutorial"
            )
            
            // If tutorial is already done, complete onboarding
            if (state.tutorialComplete) {
                SagaTransition.Complete(
                    effects = listOf(
                        SagaEffect.Dispatch(OnboardingCompleted(state.userId))
                    )
                )
            } else {
                SagaTransition.Continue(
                    newState,
                    effects = listOf(
                        SagaEffect.Dispatch(ShowTutorialScreen(state.userId))
                    )
                )
            }
        }
        
        // Handle tutorial completion
        saga.on<TutorialFinished>(
            condition = { action, state -> action.userId == state.userId }
        ) { action, state ->
            val newState = state.copy(
                tutorialComplete = true,
                currentStep = "completed"
            )
            
            // If profile is already complete, finish onboarding
            if (state.profileComplete) {
                SagaTransition.Complete(
                    effects = listOf(
                        SagaEffect.Dispatch(OnboardingCompleted(state.userId))
                    )
                )
            } else {
                SagaTransition.Continue(
                    newState,
                    effects = listOf(
                        SagaEffect.Dispatch(ShowProfileSetupScreen(state.userId))
                    )
                )
            }
        }
    }
}

// Add saga middleware to store
val store = createStore(AppState()) {
    middleware {
        sagas {
            register(OnboardingSaga())
            
            // Or define inline
            saga<PaymentSagaState>(
                name = "payment",
                initialState = { PaymentSagaState() }
            ) {
                startsOn<InitiatePayment> { action ->
                    SagaTransition.Continue(
                        PaymentSagaState(orderId = action.orderId),
                        effects = listOf(
                            SagaEffect.Dispatch(ProcessPayment(action.orderId)),
                            SagaEffect.Delay(30000), // 30 second timeout
                            SagaEffect.Dispatch(PaymentTimeout(action.orderId))
                        )
                    )
                }
            }
        }
    }
}
```

### Custom Async Actions

Create specialized async actions with custom lifecycle:

```kotlin
// Define custom async interface
interface NetworkAction<T> : AsyncAction<T> {
    data class Loading(override val initiatedBy: Action) : NetworkAction<Nothing>, AsyncAction.Processing
    data class Success<T>(override val initiatedBy: Action, override val data: T) : NetworkAction<T>, AsyncAction.Result<T>
    data class Failure(override val initiatedBy: Action, val error: Throwable) : NetworkAction<Nothing>, AsyncAction.Error
    data class Retry(override val initiatedBy: Action) : NetworkAction<Nothing>
}

// Implement in your action
data class FetchPosts(val userId: String) : AppAction(), NetworkAction<List<Post>> {
    override fun createProcessingAction() = NetworkAction.Loading(this)
    override fun createResultAction(data: List<Post>) = NetworkAction.Success(this, data)
    override fun createErrorAction(error: Throwable) = NetworkAction.Failure(this, error)
}

// Handle in reducer
val reducer: Reducer<AppState> = { state, action ->
    when (action) {
        is NetworkAction.Loading -> state.copy(isLoading = true)
        is NetworkAction.Success<*> -> when (action.initiatedBy) {
            is FetchPosts -> state.copy(
                posts = action.data as List<Post>,
                isLoading = false
            )
            else -> state
        }
        is NetworkAction.Failure -> state.copy(
            error = action.error.message,
            isLoading = false
        )
        is NetworkAction.Retry -> {
            // Re-dispatch original action
            store.dispatch(action.initiatedBy)
            state
        }
        else -> state
    }
}
```

### Persistence

Flexible persistence with multiple strategies:

```kotlin
// Create storage implementation
class FileStateStorage : StateStorage<AppState> {
    override suspend fun save(state: AppState) {
        File("app_state.json").writeText(Json.encodeToString(state))
    }
    
    override suspend fun load(): AppState? {
        return try {
            Json.decodeFromString(File("app_state.json").readText())
        } catch (e: Exception) {
            null
        }
    }
}

// Add persistence middleware with strategy
val persistenceMiddleware = PersistenceMiddleware(
    storage = FileStateStorage(),
    strategy = PersistenceStrategy.Debounced(500.milliseconds)
)

// For saga persistence
val sagaStorage = InMemorySagaStorage()
val sagaMiddleware = SagaMiddleware(
    sagaDefinitions = setOf(OnboardingSaga()),
    sagaStateSerializer = JsonSagaSerializer(),
    sagaStorage = sagaStorage,
    persistenceStrategy = SagaPersistenceStrategy.Combined(
        SagaPersistenceStrategy.OnCheckpoint,
        SagaPersistenceStrategy.OnCompletion
    )
)
```

### Action Caching

Optimize performance by caching expensive operations:

```kotlin
data class SearchProducts(val query: String) : AppAction(), CacheableAction {
    override val cacheKey = "search_$query"
    override val cacheDuration = 5.minutes
}

// Add caching middleware
val cacheMiddleware = CachingMiddleware<AppState>(
    cache = MapActionCache()
)
```

## Best Practices

1. **State Design**: Keep state immutable and normalized
2. **Action Design**: Use sealed classes for type-safe action hierarchies
5. **Performance**: Use `mapToPropsAsState` for Compose to minimize recomposition
6. **Persistence**: Choose appropriate strategy (Debounced for frequent updates, OnAction for critical state)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.