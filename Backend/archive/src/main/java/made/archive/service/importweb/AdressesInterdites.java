package made.archive.service.importweb;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Adresses vers lesquelles le serveur ne doit JAMAIS aller chercher un fichier à la demande d'un utilisateur (SSRF) :
 * boucle locale, réseaux privés (donc les noms internes de Docker : minio, redis, postgres…), adresses « link-local »
 * (dont les métadonnées cloud 169.254.169.254), espaces partagés/réservés/documentation, multicast, et les adresses
 * IPv6 qui ENCAPSULENT une adresse IPv4 (NAT64, 6to4, IPv4-mappée) — jugées sur l'adresse IPv4 qu'elles contiennent.
 */
public final class AdressesInterdites
{
    private AdressesInterdites() {}

    public static boolean estInterdite(InetAddress adresse)
    {
        if (adresse.isAnyLocalAddress() || adresse.isLoopbackAddress() || adresse.isLinkLocalAddress()
            || adresse.isSiteLocalAddress() || adresse.isMulticastAddress())
        {
            return true;
        }
        byte[] o = adresse.getAddress();
        return o.length == 4 ? interditV4(o) : o.length == 16 && interditV6(o);
    }

    /** true si le nom d'hôte (ou l'adresse littérale) ne résout vers AUCUNE adresse interdite. Impossible à résoudre = refusé. */
    public static boolean hoteAutorise(String hote)
    {
        try
        {
            for (InetAddress a : InetAddress.getAllByName(hote))
            {
                if (estInterdite(a)) return false;
            }
            return true;
        }
        catch (UnknownHostException e)
        {
            return false;
        }
    }

    private static boolean interditV4(byte[] o)
    {
        int a = o[0] & 0xFF, b = o[1] & 0xFF, c = o[2] & 0xFF;
        // Plages « classiques » aussi : cette fonction juge aussi l'IPv4 ENCAPSULÉE dans une adresse IPv6 (NAT64, 6to4),
        // que les méthodes d'InetAddress ne voient pas.
        return a == 127                                 // 127.0.0.0/8 boucle locale
            || a == 10                                  // 10.0.0.0/8
            || (a == 172 && (b & 0xF0) == 16)           // 172.16.0.0/12
            || (a == 192 && b == 168)                   // 192.168.0.0/16
            || (a == 169 && b == 254)                   // 169.254.0.0/16 lien-local, métadonnées cloud
            || (a & 0xF0) == 224                        // 224.0.0.0/4 multicast
            || a == 0                                   // 0.0.0.0/8 « ce réseau »
            || (a == 100 && (b & 0xC0) == 64)           // 100.64.0.0/10 espace partagé (CGNAT, certains réseaux privés virtuels)
            || (a == 192 && b == 0 && c == 0)           // 192.0.0.0/24 protocoles IETF
            || (a == 192 && b == 0 && c == 2)           // 192.0.2.0/24 documentation
            || (a == 198 && (b & 0xFE) == 18)           // 198.18.0.0/15 bancs d'essai
            || (a == 198 && b == 51 && c == 100)        // 198.51.100.0/24 documentation
            || (a == 203 && b == 0 && c == 113)         // 203.0.113.0/24 documentation
            || (a & 0xF0) == 240;                       // 240.0.0.0/4 réservé, dont 255.255.255.255
    }

    private static boolean interditV6(byte[] o)
    {
        int o0 = o[0] & 0xFF, o1 = o[1] & 0xFF;
        if ((o0 & 0xFE) == 0xFC) return true;                                        // fc00::/7 unique local
        if (o0 == 0x20 && o1 == 0x01 && o[2] == 0 && o[3] == 0) return true;        // 2001::/32 Teredo (tunnel)
        if (o0 == 0x20 && o1 == 0x01 && o[2] == 0x0D && (o[3] & 0xFF) == 0xB8) return true; // 2001:db8::/32 documentation
        if (o0 == 0x20 && o1 == 0x02) return interditV4(new byte[] { o[2], o[3], o[4], o[5] });   // 2002::/16 6to4
        if (o0 == 0x00 && o1 == 0x64 && (o[2] & 0xFF) == 0xFF && (o[3] & 0xFF) == 0x9B)            // 64:ff9b::/96 NAT64
        {
            return interditV4(new byte[] { o[12], o[13], o[14], o[15] });
        }
        boolean dixPremiersNuls = true;
        for (int i = 0; i < 10; i++) dixPremiersNuls &= o[i] == 0;
        if (dixPremiersNuls && (o[10] & 0xFF) == 0xFF && (o[11] & 0xFF) == 0xFF)     // ::ffff:0:0/96 IPv4-mappée
        {
            byte[] v4 = { o[12], o[13], o[14], o[15] };
            return interditV4(v4);
        }
        return false;
    }
}
