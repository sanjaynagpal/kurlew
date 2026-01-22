package io.kurlew.pipeline

/**
 * Registry for shared services.
 *
 * Provides access to databases, API clients, and other services
 * that should be available to all interceptors.
 */
class ServiceRegistry {
    private val services = mutableMapOf<String, Any>()

    /**
     * Registers a service.
     */
    fun register(name: String, service: Any) {
        services[name] = service
    }

    /**
     * Retrieves a service by name.
     */
    fun get(name: String): Any? {
        return services[name]
    }

    /**
     * Checks if a service is registered.
     */
    fun has(name: String): Boolean {
        return services.containsKey(name)
    }
}