# duks Persistence

This package provides persistence capabilities for the duks state management library.

## Core Components

### 1. Storage Interfaces

- **StateStorage<TState, TData>**: Flexible storage supporting any data format
- **StateSerializer<TState, TData>**: Serialization to any format
- **IdentitySerializer**: Special serializer for direct state storage
- **VersionedStateStorage**: Extended storage with versioning support

### 2. Persistence Strategies

- **OnEveryChange**: Persist on every state change
- **Debounced**: Persist after a delay with no further changes
- **Throttled**: Persist at most once per interval
- **OnAction**: Persist only when specific actions are dispatched
- **Conditional**: Persist based on custom state comparison
- **Combined**: Combine multiple strategies

### 3. Storage Implementations

- **InMemoryStorage**: Simple in-memory storage for testing
- **CompositeStorage**: Delegate to multiple storage implementations

## Usage Examples
```kotlin
// Create a store with efficient string-based persistence
val store = createStore(initialState = MyState()) {
    reduceWith(::myReducer)
    middleware {
        // Use persistence - no ByteArray conversion needed!
        persistence(
            storage = MyStringStorage("my_app_state.json"),
            serializer = MyJsonSerializer(),
            strategy = PersistenceStrategy.Debounced(500),
            errorHandler = { error ->
                println("Persistence error: $error")
            }
        )
    }
}

// Or use direct storage without any serialization
val directStore = createStore(initialState = MyState()) {
    reduceWith(::myReducer)
    middleware {
        persistence(
            storage = MyDatabaseStorage(),
            serializer = IdentitySerializer(),
            strategy = PersistenceStrategy.OnEveryChange
        )
    }
}
```

## Implementation Status

The persistence feature is implemented with the following components:

1. ✅ Core interfaces (StateStorage, StateSerializer)
2. ✅ Persistence strategies
3. ✅ PersistenceMiddleware with lifecycle support
4. ✅ Common storage implementations
5. ✅ Platform-specific storage
6. ✅ Integration with KStore middleware DSL

## Known Limitations

1. Android PlatformStorage requires context initialization
2. WASM/JS implementation uses in-memory storage by default
3. State restoration happens asynchronously during store initialization

## Storage Formats

The storage layer now supports any data format through generic type parameters:

```kotlin
// ByteArray storage (traditional)
class MyByteStorage : StateStorage<MyState, ByteArray> { }

// String storage (for JSON, XML, etc.)
class MyJsonStorage : StateStorage<MyState, String> { }

// Direct state storage (no serialization)
class MyDirectStorage : StateStorage<MyState, MyState> { }

// Custom format storage
data class MyFormat(val version: Int, val data: String)
class MyCustomStorage : StateStorage<MyState, MyFormat> { }
```

## Benefits

1. **Performance**: Eliminate unnecessary ByteArray conversions
2. **Type Safety**: Storage format enforced at compile time
3. **Flexibility**: Use the most appropriate format for your backend
4. **Simplicity**: Direct storage without serialization overhead

## Future Enhancements

1. Add kotlinx-serialization support
2. Implement migration support
3. Add encryption decorators
4. Implement partial state persistence
5. Add remote backup storage options