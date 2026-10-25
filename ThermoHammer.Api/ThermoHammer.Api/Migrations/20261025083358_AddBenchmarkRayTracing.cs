using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkRayTracing : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<bool>(
                name: "RayTracing",
                table: "BenchmarkRuns",
                type: "bit",
                nullable: false,
                defaultValue: false);

            migrationBuilder.AddColumn<double>(
                name: "Test3Fps",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "Test3LowFps",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "Test3Score",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "RayTracing",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Test3Fps",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Test3LowFps",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Test3Score",
                table: "BenchmarkRuns");
        }
    }
}
