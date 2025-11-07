package nl.ctasoftware.crypto.ticker.server.service.user;

import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.repository.UserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class Px75UserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public Px75UserDetailsService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    @CacheEvict(cacheNames = {"usersByUsername", "usersByUserId", "usersByEmail"}, allEntries = true)
    public Px75User addUser(Px75User user) {
        if (!StringUtils.hasText(user.getUsername())) throw new IllegalArgumentException("Username is required");
        if (!StringUtils.hasText(user.getPassword())) throw new IllegalArgumentException("Password is required");
        if (!StringUtils.hasText(user.getEmail())) throw new IllegalArgumentException("Email is required");

        if (userRepository.existsByUsername(user.getUsername()))
            throw new IllegalArgumentException("Username already exists: " + user.getUsername());
        if (userRepository.existsByEmail(user.getEmail()))
            throw new IllegalArgumentException("Email already exists: " + user.getEmail());

        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // default role if none provided
        Set<Px75Role> roles = user.getRoles();
        if (roles == null || roles.isEmpty()) {
            roles = new HashSet<>();
            roles.add(Px75Role.USER); // adjust to your enum constants
            user.setRoles(roles);
        }

        return userRepository.save(user);
    }

    public List<Px75User> getUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "username"));
    }

    @Override
    @Cacheable(cacheNames = "usersByUsername", key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return getPx75User(username);
    }

    @Cacheable(cacheNames = "usersByUserId", key = "#userId")
    public Px75User getPx75UserById(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Cacheable(cacheNames = "usersByUsername", key = "#username")
    public Px75User getPx75User(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Cacheable(cacheNames = "usersByEmail", key = "#email")
    public Px75User getPx75UserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Transactional
    @CacheEvict(cacheNames = {"usersByUsername", "usersByUserId", "usersByEmail"}, allEntries = true)
    public Px75User updateUser(long userId, Px75User changes) {
        Px75User current = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (StringUtils.hasText(changes.getUsername()) && !changes.getUsername().equals(current.getUsername())) {
            userRepository.findByUsername(changes.getUsername())
                    .filter(u -> !u.getId().equals(current.getId()))
                    .ifPresent(u -> { throw new IllegalArgumentException("Username already exists: " + changes.getUsername()); });
            current.setUsername(changes.getUsername());
        }

        if (StringUtils.hasText(changes.getEmail()) && !changes.getEmail().equals(current.getEmail())) {
            userRepository.findByEmail(changes.getEmail())
                    .filter(u -> !u.getId().equals(current.getId()))
                    .ifPresent(u -> { throw new IllegalArgumentException("Email already exists: " + changes.getEmail()); });
            current.setEmail(changes.getEmail());
        }

        if (StringUtils.hasText(changes.getPassword())) {
            current.setPassword(passwordEncoder.encode(changes.getPassword()));
        }

        if (changes.getRoles() != null) {
            // replace roles with provided set (can be empty to strip all, if you allow that)
            current.setRoles(new HashSet<>(changes.getRoles()));
        }

        return userRepository.save(current);
    }

    @Transactional
    @CacheEvict(cacheNames = {"usersByUsername", "usersByUserId", "usersByEmail"}, allEntries = true)
    public Px75User updatePassword(long userId, String rawPassword) {
        if (!StringUtils.hasText(rawPassword)) throw new IllegalArgumentException("Password is required");
        Px75User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        user.setPassword(passwordEncoder.encode(rawPassword));
        return userRepository.save(user);
    }

    @Transactional
    @CacheEvict(cacheNames = {"usersByUsername", "usersByUserId", "usersByEmail"}, allEntries = true)
    public void deleteUser(long userId) {
        if (!userRepository.existsById(userId)) throw new UsernameNotFoundException("User not found");
        userRepository.deleteById(userId);
    }
}
