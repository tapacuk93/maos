import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every cloud an account has handed over credentials for, and how to pick
 * between them.
 *
 * AWS is the one that is wired up end to end; the rest are here because an
 * account that has given us a Hetzner token should be able to have its cheap,
 * disposable work run there instead of on an EC2 instance costing five times
 * as much for the same job. The chooser is honest about what it knows: it
 * compares published hourly rates for a rough size class, and says which it
 * picked and why, rather than pretending to model anyone's real bill.
 *
 * Credentials live here in memory, in this process, exactly like the AWS ones -
 * and are subject to the same absolute rule: none of them ever appear in a
 * prompt. The model chooses a *provider by name*; the backend does the signing.
 */
final class Clouds {

    /** What we can talk to, and what it costs for the sizes we use. */
    enum Provider {
        AWS("aws", "Amazon Web Services", 0.0168, true,
                List.of("access_key", "secret_key", "region")),
        HETZNER("hetzner", "Hetzner Cloud", 0.0063, true,
                List.of("api_token")),
        DIGITALOCEAN("digitalocean", "DigitalOcean", 0.0089, true,
                List.of("api_token")),
        ALIBABA("alibaba", "Alibaba Cloud", 0.0110, true,
                List.of("access_key_id", "access_key_secret", "region_id")),
        GCP("gcp", "Google Cloud", 0.0173, false,
                List.of("service_account_json", "project_id")),
        AZURE("azure", "Microsoft Azure", 0.0182, false,
                List.of("tenant_id", "client_id", "client_secret", "subscription_id"));

        final String id;
        final String label;
        /** USD per hour for roughly two vCPUs and 4 GB, for ordering only. */
        final double hourly;
        /** Whether provisioning is actually implemented, or only credentials. */
        final boolean provisions;
        final List<String> fields;

        Provider(String id, String label, double hourly, boolean provisions, List<String> fields) {
            this.id = id;
            this.label = label;
            this.hourly = hourly;
            this.provisions = provisions;
            this.fields = fields;
        }

        static Provider of(String id) {
            if (id == null) return null;
            String k = id.toLowerCase(Locale.ROOT).trim();
            for (var p : values()) if (p.id.equals(k)) return p;
            return null;
        }
    }

    /** One provider's credentials for one account. Never leaves the process. */
    record Credential(Provider provider, Map<String, String> fields) {

        boolean complete() {
            for (var f : provider.fields) {
                var v = fields.get(f);
                if (v == null || v.isBlank()) return false;
            }
            return true;
        }
    }

    /**
     * The backend's own cloud access, lent to accounts that brought none.
     *
     * Asking somebody to paste an access key and a secret before they can run
     * a line of code is asking them to open an AWS account, work out what a
     * policy is, and get it wrong once - to run something that takes two
     * seconds. Almost nobody does it, and the ones who try arrive with
     * credentials that half work, which is worse than none: the editor issues
     * a key, accepts the request, and then reports an authentication failure
     * from four layers down.
     *
     * So the backend can carry credentials of its own, and an account that
     * gave none is bound to those at registration. From inside the editor
     * there is nothing to notice - which is the point. Running something is
     * not supposed to be an act of cloud administration.
     *
     * Static because it belongs to the process rather than to any account,
     * and one field rather than a provider map because AWS is the only one
     * provisioning is written for; the day a second is, this becomes a map
     * and the callers do not change.
     *
     * Whoever runs the backend is paying for this. That is the honest reading
     * of the arrangement and the reason it is opt-in: no environment
     * variable, no house credentials, and registration goes back to requiring
     * the caller's own.
     */
    private static volatile Credential house;

    static void house(Map<String, String> fields) {
        var c = new Credential(Provider.AWS, fields);
        house = c.complete() ? c : null;
    }

    /** The backend's own AWS access, or null if it was never given any. */
    static Credential house() { return house; }

    private final Map<Provider, Credential> bound = new LinkedHashMap<>();

    /** Bind, or replace, one provider's credentials. */
    void bind(Provider provider, Map<String, String> fields) {
        bound.put(provider, new Credential(provider, Map.copyOf(fields)));
    }

    Credential get(Provider p) { return bound.get(p); }

    boolean has(Provider p) {
        var c = bound.get(p);
        return c != null && c.complete();
    }

    List<Provider> available() {
        var out = new ArrayList<Provider>();
        for (var e : bound.entrySet()) if (e.getValue().complete()) out.add(e.getKey());
        return out;
    }

    /**
     * What the model may choose between, as text it can act on. Names only -
     * there is nothing here worth intercepting.
     */
    String briefing() {
        var usable = available();
        if (usable.isEmpty()) return "No cloud credentials are bound to this account.\n";
        var b = new StringBuilder("Clouds available to this account, cheapest first:\n");
        usable.stream()
              .sorted((x, y) -> Double.compare(x.hourly, y.hourly))
              .forEach(p -> b.append("  - ").append(p.id)
                             .append(" (").append(p.label).append(", about $")
                             .append(String.format("%.4f", p.hourly)).append("/hour")
                             .append(p.provisions ? "" : ", credentials only for now")
                             .append(")\n"));
        b.append("Say which one you want work run on and why; the backend does the provisioning.\n");
        return b.toString();
    }

    /**
     * Pick a provider for a piece of work.
     *
     * @param needsAws true when the work names AWS resources specifically, in
     *                 which case cheapness is irrelevant - the data is there
     * @return the chosen provider and the reason, or null when nothing is bound
     */
    Choice choose(boolean needsAws) {
        var usable = available();
        if (usable.isEmpty()) return null;
        if (needsAws && has(Provider.AWS)) {
            return new Choice(Provider.AWS, "the work names AWS resources, so it has to run there");
        }
        var cheapest = usable.stream()
                .filter(p -> p.provisions)
                .min((x, y) -> Double.compare(x.hourly, y.hourly))
                .orElse(null);
        if (cheapest == null) {
            return new Choice(usable.get(0),
                    "only credentials are bound for these; provisioning is not implemented yet");
        }
        return new Choice(cheapest, "cheapest provider that can actually be provisioned right now");
    }

    record Choice(Provider provider, String reason) {}
}
