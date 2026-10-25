using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkCpu : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "CpuCrypto",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "CpuData",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "CpuMath",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "CpuMulti",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "CpuScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "CpuCrypto",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "CpuData",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "CpuMath",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "CpuMulti",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "CpuScore",
                table: "BenchmarkRuns");
        }
    }
}
