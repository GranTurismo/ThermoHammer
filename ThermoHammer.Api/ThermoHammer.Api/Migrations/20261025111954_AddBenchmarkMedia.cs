using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkMedia : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<double>(
                name: "GalleryAvif",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "GalleryJpeg",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "GalleryScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "GalleryWebp",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "PhotoEdit",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "PhotoScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "ScrollDropped",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "ScrollScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "ScrollSpeed",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "VideoAv1",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<double>(
                name: "VideoAvc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "VideoHardware",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "VideoHevc",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);

            migrationBuilder.AddColumn<int>(
                name: "VideoScore",
                table: "BenchmarkRuns",
                type: "int",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<double>(
                name: "VideoVp9",
                table: "BenchmarkRuns",
                type: "float",
                nullable: false,
                defaultValue: 0.0);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "GalleryAvif",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "GalleryJpeg",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "GalleryScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "GalleryWebp",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "PhotoEdit",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "PhotoScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ScrollDropped",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ScrollScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "ScrollSpeed",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoAv1",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoAvc",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoHardware",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoHevc",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoScore",
                table: "BenchmarkRuns");

            migrationBuilder.DropColumn(
                name: "VideoVp9",
                table: "BenchmarkRuns");
        }
    }
}
