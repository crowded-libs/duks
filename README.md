
<p align="center">
  <img src="duks-logo.png" alt="Duks Logo" width="200" />
</p>

# Duks - Kotlin Compose State Management and Control Flow

Duks is a lightweight, type-safe state management library for Kotlin Multiplatform applications, inspired by Redux. It provides a predictable, unidirectional data flow pattern with built-in support for middleware and Compose UI integration.

[![Build](https://github.com/crowded-libs/duks/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks%22)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Type-safe API** - Built with Kotlin's type system for compile-time safety
- **Compose Integration** - Seamless integration with Jetpack Compose
- **Middleware Support** - Extensible middleware system for handling side effects
- **Saga Pattern** - First-class support for complex asynchronous workflows
- **Multiplatform** - Works across Android, iOS, and JVM platforms
- **Lightweight** - Minimal dependencies and small footprint
- **Action Caching** - Optional caching mechanism for performance optimization

## Installation

Add Duks to your project by including it in your Gradle build file:

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks:0.1.0")
}
```

## Basic Usage

### 1. Define Your State

Start by defining your application state as an immutable data class:

```kotlin
data class CounterState(val count: Int = 0) : StateModel
```

### 2. Define Actions

Create actions that represent events in your application:

```kotlin
// Simple action
data class Increment(val amount: Int = 1) : Action

// Async action example
data class FetchUserData(val userId: String) : AsyncAction<UserData> {
    override suspend fun execute(): Result<UserData> {
        return try {
            // Perform async operation like API call
            val userData = userRepository.fetchUserData(userId)
            Result.success(userData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 3. Create a Reducer

Define how actions transform the current state into a new state:

```kotlin
val counterReducer: Reducer<CounterState> = { state, action ->
    when (action) {
        is Increment -> state.copy(count = state.count + action.amount)
        is AsyncSuccessAction<*, *> -> {
            if (action.initiator is FetchUserData && action.result is UserData) {
                // Handle async success
                state.copy(/* update state with user data */)
            } else {
                state
            }
        }
        else -> state
    }
}
```

### 4. Create and Use the Store

Set up the store with your state, middleware, and reducer:

```kotlin
val store = createStore(CounterState()) {
    middleware {
        // Add built-in middleware
        logging()
        async()

        // Add custom middleware
        middleware { store, action, next ->
            println("Custom middleware: $action")
            next(action)
        }

        // Add sagas for complex workflows
        sagas {
            on<FetchUserData> { action ->
                // Handle the action in a saga
                val result = execute(action)
                if (result.isSuccess) {
                    put(UpdateUserAction(result.getOrNull()))
                }
            }
        }
    }

    // Add your reducer
    reduceWith(counterReducer)
}

// Dispatch actions
store.dispatch(Increment(1))
store.dispatch(FetchUserData("user-123"))
```

## Compose Integration

Duks integrates smoothly with Jetpack Compose. The store's state is exposed as a Compose `State<T>` object that triggers recomposition when updated:

```kotlin
@Composable
fun CounterScreen(store: KStore<CounterState>) {
    // Access store state in a Compose-friendly way
    val state by remember { store.state }

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
    // Remember the state from the store
    val state by remember { store.state }

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

This example demonstrates how to:
1. Define your state model and actions
2. Create a reducer to handle state transitions
3. Set up the store with middleware
4. Use the store within Compose components
5. Access and observe the state using `remember { store.state }`
6. Dispatch actions in response to user interactions

### Real World Example

Here's a more advanced example that demonstrates good practices for structuring a real-world application with Duks:

```kotlin
// 1. Define the state
data class AppState(
    val counter: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
) : StateModel

// 2. Define actions
sealed class CounterAction : Action {
    object Increment : CounterAction()
    object Decrement : CounterAction()
    data class SetCounter(val value: Int) : CounterAction()
}

// 3. Create the reducer
val counterReducer: Reducer<AppState> = { state, action ->
    when (action) {
        is CounterAction.Increment -> state.copy(counter = state.counter + 1)
        is CounterAction.Decrement -> state.copy(counter = state.counter - 1)
        is CounterAction.SetCounter -> state.copy(counter = action.value)
        else -> state
    }
}

// 4. Initialize the Store as a companion object with DSL
class Store private constructor() {
    companion object {
        // Initialize the store with DSL
        val instance by lazy {
            createStore(AppState()) {
                middleware {
                    logging()
                    async()
                }
                reduceWith(counterReducer)
            }
        }
    }
}

// 5. Set up global app state and dispatch function
val appState by lazy { Store.instance.state }
fun dispatch(action: Action) = Store.instance.dispatch(action)

// 6. Create props data class for the screen
data class CounterScreenProps(
    val counter: Int,
    val onIncrement: () -> Unit,
    val onDecrement: () -> Unit,
    val onSetCounter: (Int) -> Unit
)

// 7. Create Screen function that maps state to props using mapToProps extension
@Composable
fun CounterScreen() {
    // Use mapToProps to extract and memoize only the needed slice of state
    val props = appState.mapToProps { 
        CounterScreenProps(
            counter = counter,
            onIncrement = { dispatch(CounterAction.Increment) },
            onDecrement = { dispatch(CounterAction.Decrement) },
            onSetCounter = { value -> dispatch(CounterAction.SetCounter(value)) }
        )
    }
    CounterScreenContent(props)
}

// 8. Create ScreenContent function that only takes props
@Composable
fun CounterScreenContent(props: CounterScreenProps) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Counter: ${props.counter}",
            style = MaterialTheme.typography.h4
        )

        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = props.onDecrement) {
                Text("-")
            }

            Button(onClick = props.onIncrement) {
                Text("+")
            }
        }

        // Example of using the onSetCounter function
        Button(
            onClick = { props.onSetCounter(0) },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Reset")
        }
    }
}

// 9. Preview function that uses only the props
@Preview
@Composable
fun CounterScreenPreview() {
    // Create mock props for preview
    val previewProps = CounterScreenProps(
        counter = 42,
        onIncrement = {},
        onDecrement = {},
        onSetCounter = {}
    )

    // Use the content function with mock props
    CounterScreenContent(previewProps)
}
```

This real-world example demonstrates several important patterns:

1. **Store Initialization with DSL**: The Store is initialized as a companion object using the DSL, making it accessible throughout the application.

2. **Global App State**: The app state is set up as a global variable using `lazy` initialization, ensuring it's only created when needed.

3. **Global Dispatch Function**: A global dispatch function is created that points to the store's dispatch function, simplifying action dispatching.

4. **Screen/ScreenContent Pattern**: 
   - `CounterScreen` function handles state mapping and connects to the store
   - `CounterScreenContent` function only takes props and is completely decoupled from the store

5. **Preview-Friendly Setup**: The `CounterScreenPreview` function demonstrates how to create mock props for previewing the UI without needing a real store.

### Benefits of This Approach

1. **Separation of Concerns**: 
   - The ScreenContent function is pure and only deals with UI rendering based on props
   - The Screen function handles state mapping and store interaction

2. **Testability**: 
   - UI components can be tested in isolation by providing mock props
   - Business logic in reducers can be tested separately from UI

3. **Preview Support**: 
   - Compose previews work without requiring a real store or state
   - Designers can see and modify UI without worrying about state management

4. **Maintainability**:
   - Clear separation between UI and state management makes the codebase easier to maintain
   - Changes to state management don't require changes to UI components and vice versa

5. **Performance with mapToProps**:
   - The `mapToProps` extension method memoizes the selected slice of state
   - Only triggers recomposition when the selected data actually changes
   - Prevents unnecessary recompositions when unrelated parts of the state change
   - Automatically handles the extraction of only the needed properties from the state
   - Simplifies the code by combining state selection and prop mapping in one step

### Middleware Chaining

Middleware can be chained to process actions in sequence:

```kotlin
middleware {
    logging()  // Log all actions
    caching()  // Cache results of cacheable actions
    async()    // Handle async actions
    sagas { /* saga definitions */ }
}
```

### Sagas for Complex Workflows

Use sagas to handle complex workflows, especially asynchronous operations:
A common scenario might be when a user logs in successfully, fire off several requests concurrently to load important information related to the user's account. This is just one of many, but hopefully some of these samples provide some inspiration.

```kotlin
sagas {
    // React to specific action types
    on<UserLoginAction> { action ->
        // Execute an async action and get the result
        val result = execute(FetchUserProfile(action.userId))

        if (result.isSuccess) {
            // Dispatch additional actions
            put(UpdateUserProfileAction(result.getOrNull()!!))
            put(NavigateToHomeAction())
        } else {
            put(ShowErrorAction(result.exceptionOrNull()?.message ?: "Unknown error"))
        }
    }

    // React to successful async actions
    onSuccessOf<FetchUserData, UserData> { initiator, result ->
        // Do something when FetchUserData succeeds
        put(UserDataFetchedAction(initiator.userId, result))
    }

    // Execute actions in parallel
    on<InitializeAppAction> { _ ->
        parallel(
            FetchUserData("current-user"),
            FetchAppConfig(),
            FetchNotifications()
        )
    }

    // Execute actions in sequence
    on<CheckoutAction> { action ->
        chain(
            ValidateCartAction(action.cartId),
            ProcessPaymentAction(action.paymentDetails),
            CreateOrderAction(action.cartId, action.userId),
            SendOrderConfirmationAction(action.userId)
        )
    }
}
```

### Action Caching

Improve performance by caching action results:

```kotlin
// Define a cacheable action
data class FetchProductDetails(val productId: String) : CacheableAction, AsyncAction<ProductDetails> {
    override val cacheKey: String = "product-$productId"

    override suspend fun execute(): Result<ProductDetails> {
        return try {
            // Perform async operation to fetch product details
            val details = productRepository.getProductDetails(productId)
            Result.success(details)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Add caching middleware
middleware {
    caching()
}
```

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.
