package pt.flawless.fdatabase;

/** Configuração da base de dados inválida ou incompleta. A mensagem nunca contém a password. */
public class DatabaseConfigException extends RuntimeException {

    DatabaseConfigException(String message) {
        super(message);
    }
}
