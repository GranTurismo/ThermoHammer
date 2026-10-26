using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkVideoExport : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "Export8kHevc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "Export8kHevcPsnr",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "ExportAv1",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "ExportAv1Psnr",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "ExportAvc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "ExportAvcPsnr",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "ExportHardware",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "ExportHevc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "ExportHevcPsnr",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "ExportScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "Video8kAv1",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "Video8kHevc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "Export8kHevc",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Export8kHevcPsnr",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportAv1",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportAv1Psnr",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportAvc",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportAvcPsnr",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportHardware",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportHevc",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportHevcPsnr",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ExportScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Video8kAv1",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "Video8kHevc",
                table: "BenchmarkRuns");
        }
    }
}
