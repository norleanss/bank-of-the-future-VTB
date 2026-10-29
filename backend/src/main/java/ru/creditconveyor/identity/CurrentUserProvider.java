package ru.creditconveyor.identity;

/**
 * Единственный способ для модулей узнать текущего пользователя; реализация читает его из JWT.
 */
public interface CurrentUserProvider {

    CurrentUser currentUser();
}
