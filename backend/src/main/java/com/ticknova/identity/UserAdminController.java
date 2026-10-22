package com.ticknova.identity;

import com.ticknova.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserAdminController {
    private final UserAdminService users;
    public UserAdminController(UserAdminService users) { this.users=users; }
    @GetMapping("/users") public PageResult<UserAdminService.UserView> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return users.list(CurrentUser.get(),page,size); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public UserAdminService.UserView create(@Valid @RequestBody CreateUser body) { return users.create(CurrentUser.get(),body.username(),body.displayName(),body.password(),body.roles()); }
    @PatchMapping("/users/{id}") public UserAdminService.UserView patch(@PathVariable UUID id,@Valid @RequestBody PatchUser body) { return users.patch(CurrentUser.get(),id,body.displayName(),body.active()); }
    @PutMapping("/users/{id}/roles") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setRoles(@PathVariable UUID id,@Valid @RequestBody SetRoles body) { users.setRoles(CurrentUser.get(),id,body.roles()); }
    @GetMapping("/roles") public List<String> roles() { return users.roles(CurrentUser.get()); }
    public record CreateUser(@NotBlank @Size(min=3,max=80) String username,@NotBlank @Size(max=120) String displayName,@NotBlank @Size(min=12,max=72) String password,@NotEmpty Set<@NotBlank String> roles) {}
    public record PatchUser(@Size(min=1,max=120) String displayName,Boolean active) {}
    public record SetRoles(@NotEmpty Set<@NotBlank String> roles) {}
}
