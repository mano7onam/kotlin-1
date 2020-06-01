expect class A()

// Lifted up type aliases:
typealias B = A // class at the RHS
typealias C = B // TA at the RHS
typealias D = List<String> // parameterized type at the RHS
typealias E<T> = List<T> // TA with own parameters
typealias F<R> = Function<R> // function type at the RHS
typealias G = () -> Unit // function type at the RHS
typealias H = (String) -> Int // function type at the RHS

typealias I<T> = (List<G>) -> Map<T, H> // something complex
typealias J = Function<C> // something complex

// Type aliases converted to expect classes:
typealias K = String
expect class L
