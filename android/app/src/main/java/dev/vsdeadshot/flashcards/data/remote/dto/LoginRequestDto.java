package dev.vsdeadshot.flashcards.data.remote.dto;

/** The body of {@code POST /auth/login}. The passphrase, and nothing else. */
public class LoginRequestDto {

    public final String passphrase;

    public LoginRequestDto(String passphrase) {
        this.passphrase = passphrase;
    }
}
