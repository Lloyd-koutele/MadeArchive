package made.archive.security;

import java.util.Collection;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import made.archive.entite.User;

public class UserDetailsImpl implements UserDetails 
{

    private final User user;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserDetailsImpl(User user, Collection<? extends GrantedAuthority> authorities) 
    {
        this.user = user;
        this.authorities = authorities;
    }

    public UUID getId()
    {
        return user.getId(); 
    }

    public User getUser()
    { 
        return user; 
    }

    @Override public String getUsername()
    {
        return user.getEmail(); 
    }
    @Override public String getPassword() 
    {
        return user.getPassword(); 
    }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActif(); }
}