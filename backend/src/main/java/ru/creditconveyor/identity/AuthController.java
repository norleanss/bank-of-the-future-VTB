package ru.creditconveyor.identity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import ru.creditconveyor.api.AuthApi;
import ru.creditconveyor.api.BusinessTypeDto;
import ru.creditconveyor.api.DemoLoginRequestDto;
import ru.creditconveyor.api.LoginResponseDto;
import ru.creditconveyor.api.RoleDto;
import ru.creditconveyor.api.UserCompanyDto;
import ru.creditconveyor.api.UserDto;

@RestController
class AuthController implements AuthApi {

    private final AccessTokens accessTokens;
    private final CurrentUserProvider currentUserProvider;
    private final UserCompanyLinkRepository links;
    private final CompanyRepository companies;
    private final String clientLogin;
    private final String underwriterLogin;

    AuthController(AccessTokens accessTokens,
                   CurrentUserProvider currentUserProvider,
                   UserCompanyLinkRepository links,
                   CompanyRepository companies,
                   @Value("${app.demo.client-login}") String clientLogin,
                   @Value("${app.demo.underwriter-login}") String underwriterLogin) {
        this.accessTokens = accessTokens;
        this.currentUserProvider = currentUserProvider;
        this.links = links;
        this.companies = companies;
        this.clientLogin = clientLogin;
        this.underwriterLogin = underwriterLogin;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<LoginResponseDto> demoLogin(DemoLoginRequestDto request) {
        String login = request.getRole() == RoleDto.CLIENT ? clientLogin : underwriterLogin;
        AccessTokens.IssuedToken issued = accessTokens.issueFor(login);
        return ResponseEntity.ok(new LoginResponseDto(
                issued.accessToken(), Math.toIntExact(issued.expiresInSeconds()), toDto(issued.user())));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<UserDto> getMe() {
        return ResponseEntity.ok(toDto(currentUserProvider.currentUser()));
    }

    private UserDto toDto(CurrentUser user) {
        UserDto dto = new UserDto(user.id(), user.fullName(), user.role());
        if (user.role() != RoleDto.CLIENT) {
            // Сгенерированный DTO держит пустой список, а у сотрудника банка поля быть не должно
            dto.setCompanies(null);
            return dto;
        }
        List<UserCompanyLink> userLinks = links.findByUserIdOrderBySortOrder(user.id());
        Map<UUID, Company> byId = companies.findAllById(userLinks.stream().map(UserCompanyLink::getCompanyId).toList())
                .stream()
                .collect(Collectors.toMap(Company::getId, Function.identity()));
        return dto.companies(userLinks.stream()
                .map(link -> {
                    Company company = byId.get(link.getCompanyId());
                    return new UserCompanyDto(company.getId(), company.getName(), company.getInn(),
                            BusinessTypeDto.fromValue(company.getBusinessType()))
                            .demoScenario(link.getDemoScenario());
                })
                .toList());
    }
}
