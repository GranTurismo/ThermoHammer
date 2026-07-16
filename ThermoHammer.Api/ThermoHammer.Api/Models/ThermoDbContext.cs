using Microsoft.EntityFrameworkCore;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Models;

public class ThermoDbContext(DbContextOptions<ThermoDbContext> options) : DbContext(options)
{
    public DbSet<ThermoSession> Sessions => Set<ThermoSession>();
    public DbSet<Hammer> Hammers => Set<Hammer>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.Entity<Hammer>()
        .HasMany(o => o.Stamps)
        .WithOne()
        .HasForeignKey(o => o.HammerId)
        .HasPrincipalKey(o => o.Id);

        modelBuilder.Entity<Hammer>()
        .HasOne(o => o.Session)
        .WithOne()
        .HasForeignKey<Hammer>(o => o.SessionId)
        .HasPrincipalKey<ThermoSession>(o => o.Id);
    }
}