package com.korosoft.keyinput;

/**
 * Single source of truth for every player-facing string in the login/register card
 * ({@link LoginScreen}), matching {@link DisclaimerText}'s convention of keeping copy apart from
 * rendering logic. All text is Spanish (neutral, "tú" form) — no voseo, per project convention.
 */
public final class AuthText {

    private AuthText() {
    }

    public static final String LOGIN_TITLE = "Iniciar sesión";
    public static final String REGISTER_TITLE = "Crear cuenta";

    public static final String ACCOUNT_CAPTION = "Tu cuenta";
    public static final String PASSWORD_PLACEHOLDER = "Contraseña";
    public static final String CONFIRM_PASSWORD_PLACEHOLDER = "Confirma tu contraseña";

    public static final String REGISTER_LINK = "¿No tienes cuenta? Regístrate";
    public static final String LOGIN_LINK = "¿Ya tienes cuenta? Inicia sesión";
    public static final String FORGOT_PASSWORD_LINK = "¿Olvidaste tu contraseña?";
    public static final String FORGOT_PASSWORD_MESSAGE = "Contacta a un administrador.";

    public static final String LOGIN_BUTTON = "Iniciar sesión";
    public static final String REGISTER_BUTTON = "Crear cuenta";
    public static final String SUBMITTING = "Conectando…";

    /** Greeting shown on the launcher when a returning player is auto-logged-in. {@code %s} = player name. */
    public static final String WELCOME = "¡Bienvenido, %s!";

    // --- Status / error messages, mapped from the KoroAuth backend's HTTP responses. ---

    public static final String ERROR_INVALID_CREDENTIALS = "Usuario o contraseña incorrectos.";
    public static final String ERROR_NOT_WHITELISTED = "No estás en la whitelist. Contacta a un administrador.";
    public static final String ERROR_ALREADY_REGISTERED = "Ese usuario ya está registrado.";
    public static final String ERROR_INVALID_USERNAME = "Nombre de usuario inválido (3-16 caracteres, sin símbolos).";
    public static final String ERROR_RATE_LIMITED = "Demasiados intentos, espera unos minutos.";
    public static final String ERROR_BAD_REQUEST = "Escribe tu usuario y contraseña.";
    public static final String ERROR_PASSWORDS_DONT_MATCH = "Las contraseñas no coinciden.";
    public static final String ERROR_SERVER_ERROR = "Error del servidor. Inténtalo de nuevo en un momento.";
    public static final String ERROR_NETWORK = "No se pudo conectar con el servidor de autenticación.";
}
